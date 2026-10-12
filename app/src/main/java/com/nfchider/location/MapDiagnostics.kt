package com.nfchider.location

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object MapDiagnostics {

    data class NetworkStatus(
        val isConnected: Boolean,
        val isWifi: Boolean,
        val isCellular: Boolean,
        val isVpnActive: Boolean,
        val desc: String
    )

    data class TileProbeResult(
        val reachable: Boolean,
        val httpCode: Int = 0,
        val latencyMs: Long = 0,
        val errorMsg: String? = null
    )

    data class DiagnosticResult(
        val networkStatus: NetworkStatus,
        val currentSource: MapSourceItem,
        val currentProbe: TileProbeResult,
        val googleProbe: TileProbeResult?,
        val amapProbe: TileProbeResult?,
        val isDisplayAbnormal: Boolean,
        val issueTitle: String,
        val issueReason: String,
        val suggestions: List<String>,
        val canSwitchToDomestic: Boolean
    )

    fun checkNetwork(context: Context): NetworkStatus {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkStatus(
                isConnected = false,
                isWifi = false,
                isCellular = false,
                isVpnActive = false,
                desc = "无法获取网络服务"
            )

        val activeNetwork = cm.activeNetwork
        val caps = if (activeNetwork != null) cm.getNetworkCapabilities(activeNetwork) else null

        if (caps == null) {
            return NetworkStatus(
                isConnected = false,
                isWifi = false,
                isCellular = false,
                isVpnActive = false,
                desc = "无网络连接"
            )
        }

        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

        val desc = buildString {
            when {
                isWifi -> append("Wi-Fi 无线网络")
                isCellular -> append("移动蜂窝数据")
                else -> append("其他网络")
            }
            if (isVpn) {
                append(" (已开启 VPN/代理)")
            }
        }

        return NetworkStatus(
            isConnected = hasInternet,
            isWifi = isWifi,
            isCellular = isCellular,
            isVpnActive = isVpn,
            desc = desc
        )
    }

    suspend fun probeUrl(urlStr: String, timeoutMs: Int = 3000): TileProbeResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
            }
            val code = conn.responseCode
            val elapsed = System.currentTimeMillis() - start
            TileProbeResult(
                reachable = (code in 200..399),
                httpCode = code,
                latencyMs = elapsed
            )
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            TileProbeResult(
                reachable = false,
                latencyMs = elapsed,
                errorMsg = e.localizedMessage ?: e.javaClass.simpleName
            )
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 针对当前图源与网络状态进行综合诊断分析
     */
    suspend fun diagnose(context: Context, currentSource: MapSourceItem): DiagnosticResult {
        val net = checkNetwork(context)

        // 探测当前图源
        val currentProbe = if (!net.isConnected) {
            TileProbeResult(reachable = false, errorMsg = "设备未联网")
        } else {
            probeUrl(currentSource.getProbeUrl(), timeoutMs = 2800)
        }

        // 如果当前是 Google 且失败，探测高德作为对比
        var amapProbe: TileProbeResult? = null
        var googleProbe: TileProbeResult? = null

        if (currentSource == MapSourceItem.GOOGLE_HYBRID) {
            googleProbe = currentProbe
            if (!currentProbe.reachable && net.isConnected) {
                amapProbe = probeUrl(MapSourceItem.AMAP_VECTOR.getProbeUrl(), timeoutMs = 2000)
            }
        } else if (currentSource == MapSourceItem.AMAP_VECTOR || currentSource == MapSourceItem.AMAP_SATELLITE) {
            amapProbe = currentProbe
        }

        val isAbnormal = !currentProbe.reachable
        val title: String
        val reason: String
        val suggestions = mutableListOf<String>()
        val canSwitchDomestic = !currentSource.isDomestic

        when {
            !net.isConnected -> {
                title = "网络未连接"
                reason = "设备当前处于离线状态，无法下载地图瓦片，地图因此呈现空白网格。"
                suggestions.add("请检查手机 Wi-Fi 或移动蜂窝数据是否已开启并正常联网。")
            }
            isAbnormal -> {
                if (currentSource == MapSourceItem.GOOGLE_HYBRID) {
                    if (net.isVpnActive) {
                        title = "Google 地图受阻 (VPN 分应用代理未生效)"
                        reason = "系统已开启 VPN，但 Google 瓦片服务器仍连接超时或被拦截。常见原因为：代理软件（如 Clash、v2rayNG、Sing-box 等）开启了「分应用代理 / 绕过模式」，新安装的 NFC Hider 未被加入代理列表中，导致地图请求直连国内网络被防火墙拦截。"
                        suggestions.add("【方案一·最便捷】点击一键切换至「高德矢量图」或「高德卫星图」，国内直连秒开，无需任何代理！")
                        suggestions.add("【方案二】打开代理/梯子 App，在「分应用代理 / 应用绕过」设置中找到「NFC Hider」并勾选允许走代理；或者检查代理节点规则。")
                    } else {
                        title = "Google 地图国内无法直连"
                        reason = "当前使用的是 Google 卫星混合图，Google 服务器在国内大陆网络环境下受到防火墙限制无法直连访问，导致地图瓦片无法加载并显示空白网格。"
                        suggestions.add("【方案一·推荐】点击一键切换至「高德地图 (矢量/卫星)」，国内直连高速加载！")
                        suggestions.add("【方案二】开启 VPN 科学上网工具，并在分应用代理中允许 NFC Hider 走代理。")
                    }
                } else if (currentSource == MapSourceItem.OSM) {
                    title = "OpenStreetMap 瓦片加载缓慢或受阻"
                    reason = "OpenStreetMap 官方服务器位于海外，国内网络延迟高且常有丢包，可能导致瓦片下载失败显示网格。"
                    suggestions.add("推荐切换至国内直连免代理的「高德矢量地图」或「高德卫星图」。")
                } else {
                    title = "高德瓦片服务器响应异常"
                    reason = "高德服务器连接失败 (${currentProbe.errorMsg ?: "HTTP ${currentProbe.httpCode}"})，请检查当前网络状态或 DNS 解析。"
                    suggestions.add("尝试切换网络环境（如切换 Wi-Fi 与蜂窝数据），或重新加载地图。")
                }
            }
            else -> {
                title = "地图图层加载正常"
                reason = "瓦片服务器连接正常 (响应耗时 ${currentProbe.latencyMs} ms)。"
                suggestions.add("如仍有个别区域空白，可能是当前缩放级别瓦片正在后台缓存下载，缩放或平移地图即可。")
            }
        }

        return DiagnosticResult(
            networkStatus = net,
            currentSource = currentSource,
            currentProbe = currentProbe,
            googleProbe = googleProbe,
            amapProbe = amapProbe,
            isDisplayAbnormal = isAbnormal,
            issueTitle = title,
            issueReason = reason,
            suggestions = suggestions,
            canSwitchToDomestic = canSwitchDomestic
        )
    }
}

