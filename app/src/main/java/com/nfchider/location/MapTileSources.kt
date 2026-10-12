package com.nfchider.location

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

/**
 * Google Maps 卫星混合图层 (Satellite + Road/Place Labels).
 * 匹配参考图像中的高精卫星影像与地名道路标注。
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

