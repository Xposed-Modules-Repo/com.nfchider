package com.nfchider.location

import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex

/**
 * Google Maps 卫星混合图层 (Satellite + Road/Place Labels).
 * 需全局代理或在 VPN 中将本应用配置为走代理。
 */
object GoogleHybridTileSource : OnlineTileSourceBase(
    "GoogleHybrid",
    0, 20, 256, ".png",
    arrayOf(
        "https://mt0.google.com/vt/lyrs=y&hl=zh-CN&",
        "https://mt1.google.com/vt/lyrs=y&hl=zh-CN&",
        "https://mt2.google.com/vt/lyrs=y&hl=zh-CN&",
        "https://mt3.google.com/vt/lyrs=y&hl=zh-CN&"
    )
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "${baseUrl}x=$x&y=$y&z=$zoom"
    }
}

/**
 * 高德标准矢量地图 (普通道路、建筑、地名标注).
 * 国内各运营商极速直连，不丢包，无需任何代理与梯子。
 */
object AmapVectorTileSource : OnlineTileSourceBase(
    "AmapVector",
    0, 19, 256, ".png",
    arrayOf(
        "https://wprd01.is.autonavi.com/appmaptile?",
        "https://wprd02.is.autonavi.com/appmaptile?",
        "https://wprd03.is.autonavi.com/appmaptile?",
        "https://wprd04.is.autonavi.com/appmaptile?"
    )
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "${baseUrl}style=7&x=$x&y=$y&z=$zoom&lang=zh_cn&size=1&scale=1&scl=1"
    }
}

/**
 * 高德卫星影像图.
 * 国内高清卫星影像，国内各运营商极速直连。
 */
object AmapSatelliteTileSource : OnlineTileSourceBase(
    "AmapSatellite",
    0, 19, 256, ".jpg",
    arrayOf(
        "https://wprd01.is.autonavi.com/appmaptile?",
        "https://wprd02.is.autonavi.com/appmaptile?",
        "https://wprd03.is.autonavi.com/appmaptile?",
        "https://wprd04.is.autonavi.com/appmaptile?"
    )
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "${baseUrl}style=6&x=$x&y=$y&z=$zoom&lang=zh_cn&size=1&scale=1&scl=1"
    }
}

/**
 * 地图图源枚举定义与辅助类
 */
enum class MapSourceItem(
    val key: String,
    val title: String,
    val subtitle: String,
    val isDomestic: Boolean
) {
    AMAP_VECTOR(
        key = "AmapVector",
        title = "高德矢量地图",
        subtitle = "国内直连·极速稳定·推荐",
        isDomestic = true
    ),
    AMAP_SATELLITE(
        key = "AmapSatellite",
        title = "高德卫星图",
        subtitle = "国内直连·高清卫星影像",
        isDomestic = true
    ),
    GOOGLE_HYBRID(
        key = "GoogleHybrid",
        title = "Google 卫星混合图",
        subtitle = "海外/需梯子全局或分应用代理",
        isDomestic = false
    ),
    OSM(
        key = "Mapnik",
        title = "OpenStreetMap",
        subtitle = "国际开源矢量地图",
        isDomestic = false
    );

    fun getTileSource(): ITileSource = when (this) {
        AMAP_VECTOR -> AmapVectorTileSource
        AMAP_SATELLITE -> AmapSatelliteTileSource
        GOOGLE_HYBRID -> GoogleHybridTileSource
        OSM -> TileSourceFactory.MAPNIK
    }

    fun getProbeUrl(): String = when (this) {
        AMAP_VECTOR -> "https://wprd01.is.autonavi.com/appmaptile?style=7&x=13735&y=6691&z=14&lang=zh_cn&size=1&scale=1&scl=1"
        AMAP_SATELLITE -> "https://wprd01.is.autonavi.com/appmaptile?style=6&x=13735&y=6691&z=14&lang=zh_cn&size=1&scale=1&scl=1"
        GOOGLE_HYBRID -> "https://mt0.google.com/vt/lyrs=y&hl=zh-CN&x=13735&y=6691&z=14"
        OSM -> "https://tile.openstreetmap.org/0/0/0.png"
    }

    companion object {
        fun fromKey(key: String): MapSourceItem {
            return entries.firstOrNull { it.key == key } ?: AMAP_VECTOR
        }
    }
}
