package com.nfchider.location

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs

/**
 * 地点搜索数据模型
 */
data class SearchResultPlace(
    val title: String,
    val description: String,
    val lat: Double,
    val lng: Double,
    val source: String
)

/**
 * 地理位置网络搜索与经纬度解析服务
 * 支持：
 * 1. 经纬度原始数值即时解析与校验 (例如 "39.9087, 116.3975")
 * 2. Komoot Photon API (基于 OpenStreetMap，全国与全球地名快速检索，无需 API Key)
 * 3. OpenStreetMap Nominatim API (备用地理编码搜索引擎)
 */
object LocationSearchService {

    private const val USER_AGENT = "NfcHider/1.2 (Android; LocationSearch)"

    suspend fun search(query: String): List<SearchResultPlace> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()

        val results = mutableListOf<SearchResultPlace>()

        // 1. 优先解析经纬度数字格式
        val coordResults = parseCoordinates(trimmed)
        results.addAll(coordResults)

        // 2. 检索 Photon API
        val photonResults = runCatching { searchPhoton(trimmed) }.getOrDefault(emptyList())
        for (item in photonResults) {
            if (results.none { isClose(it, item) }) {
                results.add(item)
            }
        }

        // 3. 若结果偏少，尝试 Nominatim API 补充
        if (results.size < 4) {
            val nominatimResults = runCatching { searchNominatim(trimmed) }.getOrDefault(emptyList())
            for (item in nominatimResults) {
                if (results.none { isClose(it, item) }) {
                    results.add(item)
                }
            }
        }

        results
    }

    private fun isClose(a: SearchResultPlace, b: SearchResultPlace): Boolean {
        return abs(a.lat - b.lat) < 0.0001 && abs(a.lng - b.lng) < 0.0001
    }

    /**
     * 解析用户直接输入的经纬度数值
     * 例如 "39.9087, 116.3975" 或 "116.3975 39.9087"
     */
    private fun parseCoordinates(query: String): List<SearchResultPlace> {
        val regex = Regex("""^([-+]?\d+(?:\.\d+)?)[,\s\t]+([-+]?\d+(?:\.\d+)?)$""")
        val match = regex.find(query.trim()) ?: return emptyList()
        val n1 = match.groupValues[1].toDoubleOrNull() ?: return emptyList()
        val n2 = match.groupValues[2].toDoubleOrNull() ?: return emptyList()

        val list = mutableListOf<SearchResultPlace>()
        if (abs(n1) <= 90.0 && abs(n2) <= 180.0) {
            list.add(
                SearchResultPlace(
                    title = "坐标定位 (纬度, 经度)",
                    description = String.format(Locale.US, "纬度: %.6f, 经度: %.6f", n1, n2),
                    lat = n1,
                    lng = n2,
                    source = "精确坐标"
                )
            )
        }
        if (abs(n2) <= 90.0 && abs(n1) <= 180.0 && n1 != n2) {
            list.add(
                SearchResultPlace(
                    title = "坐标定位 (经度, 纬度反序)",
                    description = String.format(Locale.US, "纬度: %.6f, 经度: %.6f", n2, n1),
                    lat = n2,
                    lng = n1,
                    source = "精确坐标"
                )
            )
        }
        return list
    }

    private fun searchPhoton(query: String): List<SearchResultPlace> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val urlStr = "https://photon.komoot.io/api/?q=$encoded&lang=zh&limit=15"
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("User-Agent", USER_AGENT)
            requestMethod = "GET"
        }
        val code = conn.responseCode
        if (code != 200) return emptyList()
        val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(text)
        val features = root.optJSONArray("features") ?: return emptyList()
        val list = mutableListOf<SearchResultPlace>()
        for (i in 0 until features.length()) {
            val feat = features.optJSONObject(i) ?: continue
            val geom = feat.optJSONObject("geometry") ?: continue
            val coords = geom.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue
            val lng = coords.optDouble(0)
            val lat = coords.optDouble(1)
            val props = feat.optJSONObject("properties") ?: JSONObject()
            val name = props.optString("name").takeIf { it.isNotBlank() }
                ?: props.optString("street").takeIf { it.isNotBlank() }
                ?: query
            val parts = listOfNotNull(
                props.optString("street").takeIf { it.isNotBlank() },
                props.optString("district").takeIf { it.isNotBlank() },
                props.optString("city").takeIf { it.isNotBlank() },
                props.optString("state").takeIf { it.isNotBlank() },
                props.optString("country").takeIf { it.isNotBlank() }
            ).distinct()
            val desc = if (parts.isNotEmpty()) parts.joinToString(" · ") else String.format(Locale.US, "坐标: %.5f, %.5f", lat, lng)
            list.add(SearchResultPlace(title = name, description = desc, lat = lat, lng = lng, source = "Photon"))
        }
        return list
    }

    private fun searchNominatim(query: String): List<SearchResultPlace> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val urlStr = "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=10&accept-language=zh"
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("User-Agent", USER_AGENT)
            requestMethod = "GET"
        }
        val code = conn.responseCode
        if (code != 200) return emptyList()
        val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val array = JSONArray(text)
        val list = mutableListOf<SearchResultPlace>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val lat = item.optString("lat").toDoubleOrNull() ?: continue
            val lng = item.optString("lon").toDoubleOrNull() ?: continue
            val name = item.optString("name").takeIf { it.isNotBlank() }
                ?: item.optString("display_name").split(",").firstOrNull()?.trim()
                ?: query
            val displayName = item.optString("display_name")
            list.add(SearchResultPlace(title = name, description = displayName, lat = lat, lng = lng, source = "OSM"))
        }
        return list
    }
}

