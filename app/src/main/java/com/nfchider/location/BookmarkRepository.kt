package com.nfchider.location

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LocationBookmark(
    val id: String = System.currentTimeMillis().toString(),
    val name: String,
    val lat: Double,
    val lng: Double,
    val createTime: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("lat", lat)
            put("lng", lng)
            put("createTime", createTime)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): LocationBookmark? {
            return runCatching {
                LocationBookmark(
                    id = json.optString("id", System.currentTimeMillis().toString()),
                    name = json.getString("name"),
                    lat = json.getDouble("lat"),
                    lng = json.getDouble("lng"),
                    createTime = json.optLong("createTime", System.currentTimeMillis())
                )
            }.getOrNull()
        }
    }
}

object BookmarkRepository {
    private const val FILE_NAME = "location_bookmarks.json"

    fun load(context: Context): List<LocationBookmark> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return runCatching {
            val text = file.readText()
            val arr = JSONArray(text)
            val list = mutableListOf<LocationBookmark>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                LocationBookmark.fromJson(obj)?.let { list.add(it) }
            }
            list
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, bookmarks: List<LocationBookmark>) {
        runCatching {
            val arr = JSONArray()
            for (b in bookmarks) {
                arr.put(b.toJson())
            }
            File(context.filesDir, FILE_NAME).writeText(arr.toString())
        }
    }

    fun add(context: Context, bookmark: LocationBookmark): List<LocationBookmark> {
        val current = load(context).toMutableList()
        current.add(0, bookmark)
        save(context, current)
        return current
    }

    fun remove(context: Context, id: String): List<LocationBookmark> {
        val current = load(context).toMutableList()
        current.removeAll { it.id == id }
        save(context, current)
        return current
    }

    fun update(context: Context, bookmark: LocationBookmark): List<LocationBookmark> {
        val current = load(context).toMutableList()
        val index = current.indexOfFirst { it.id == bookmark.id }
        if (index != -1) {
            current[index] = bookmark
            save(context, current)
        }
        return current
    }
}

