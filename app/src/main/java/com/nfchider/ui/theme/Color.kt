package com.nfchider.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * NFC Hider 品牌设计系统 —— 「隐匿蓝 / Stealth Azure」
 *
 * 设计定位：工具型 Xposed 模块，界面需要专业、克制、高信息密度。
 * 主色取体系蓝（#0B57D0），强调色取青色（定位/轨迹语义），
 * 辅以语义化的地图覆盖色（路线蓝 / 录制绿 / 收藏金 / 模拟红）。
 *
 * 色彩角色命名遵循 Material 3 规范；[MapPalette] 为地图专用语义色，
 * 明暗两套，由 [MapPalette.forDark] 在运行时选择。
 */

// ---------------------------------------------------------------------
// 品牌基线
// ---------------------------------------------------------------------
val BrandBlue = Color(0xFF0B57D0)          // primary 主品牌蓝
val BrandBlueContainer = Color(0xFFD9E2FF) // primaryContainer
val BrandBlueDark = Color(0xFFB0C6FF)      // 暗色模式 primary
val BrandBlueContainerDark = Color(0xFF00458F)

val SlateSecondary = Color(0xFF565E71)     // secondary
val SlateSecondaryContainer = Color(0xFFDBE2F9)
val SlateSecondaryDark = Color(0xFFBFC6DC)
val SlateSecondaryContainerDark = Color(0xFF3F4759)

val TealTertiary = Color(0xFF00696E)       // tertiary 定位青
val TealTertiaryContainer = Color(0xFF9CF1F4)
val TealTertiaryDark = Color(0xFF82D3D8)
val TealTertiaryContainerDark = Color(0xFF004F52)

// ---------------------------------------------------------------------
// 语义色（错误 / 警告）
// ---------------------------------------------------------------------
val ErrorRed = Color(0xFFBA1A1A)
val ErrorRedContainer = Color(0xFFFFDAD6)
val ErrorRedDark = Color(0xFFFFB4AB)
val ErrorRedContainerDark = Color(0xFF93000A)

// ---------------------------------------------------------------------
// 中性色阶（浅色）
// ---------------------------------------------------------------------
val NeutralWhite = Color(0xFFFFFFFF)
val NeutralSurfaceLight = Color(0xFFFDFCFF)
val NeutralOnSurfaceLight = Color(0xFF1B1B1F)
val NeutralSurfaceVariantLight = Color(0xFFE1E2EC)
val NeutralOnSurfaceVariantLight = Color(0xFF44464F)
val NeutralOutlineLight = Color(0xFF757780)
val NeutralOutlineVariantLight = Color(0xFFC5C6D0)
val NeutralInverseSurfaceLight = Color(0xFF303034)
val NeutralInverseOnSurfaceLight = Color(0xFFF2F0F4)
val NeutralContainerLowLight = Color(0xFFF6F8FE)
val NeutralContainerLight = Color(0xFFF0F3FA)
val NeutralContainerHighLight = Color(0xFFEAEDF5)
val NeutralContainerHighestLight = Color(0xFFE4E7EF)

// ---------------------------------------------------------------------
// 中性色阶（暗色）
// ---------------------------------------------------------------------
val NeutralSurfaceDark = Color(0xFF111318)
val NeutralOnSurfaceDark = Color(0xFFE3E2E6)
val NeutralSurfaceVariantDark = Color(0xFF44464F)
val NeutralOnSurfaceVariantDark = Color(0xFFC5C6D0)
val NeutralOutlineDark = Color(0xFF8F9099)
val NeutralOutlineVariantDark = Color(0xFF44464F)
val NeutralInverseSurfaceDark = Color(0xFFE3E2E6)
val NeutralInverseOnSurfaceDark = Color(0xFF303034)
val NeutralContainerLowestDark = Color(0xFF0C0E13)
val NeutralContainerLowDark = Color(0xFF191C20)
val NeutralContainerDark = Color(0xFF1D2024)
val NeutralContainerHighDark = Color(0xFF272A2F)
val NeutralContainerHighestDark = Color(0xFF32353A)

// ---------------------------------------------------------------------
// 前景容器色（on-container 角色）
// ---------------------------------------------------------------------
val Color_OnPrimaryContainerLight = Color(0xFF001A41)
val Color_OnSecondaryContainerLight = Color(0xFF131C2C)
val Color_OnTertiaryContainerLight = Color(0xFF002021)
val Color_OnErrorContainerLight = Color(0xFF410002)

val Color_OnPrimaryDark = Color(0xFF002E69)
val Color_OnSecondaryDark = Color(0xFF293041)
val Color_OnTertiaryDark = Color(0xFF003739)
val Color_OnErrorDark = Color(0xFF690005)

// ---------------------------------------------------------------------
// 地图语义调色板（明/暗两套）
// ---------------------------------------------------------------------

/** 地图覆盖层语义色：路线、录制、收藏、模拟点、真实位置。 */
data class MapPalette(
    val routeLine: Color,        // 模拟路线折线
    val recordLine: Color,       // 真实录制航迹折线
    val bookmarkFill: Color,     // 收藏标记填充
    val bookmarkCore: Color,     // 收藏标记内核
    val simMarker: Color,        // 模拟位置标记
    val realLocationHalo: Color, // 真实位置光晕
    val realLocationCore: Color, // 真实位置核心
    val statusRecording: Color,  // 录制中状态点
    val statusPaused: Color,     // 录制暂停状态点
    val waypointLabel: Color     // 航点序号文字
) {
    companion object {
        /** 浅色地图（高德/Google 亮底）下的覆盖色。 */
        val Light = MapPalette(
            routeLine = Color(0xFF1B6FE3),
            recordLine = Color(0xFF00C853),
            bookmarkFill = Color(0xFFFFB300),
            bookmarkCore = Color(0xFFE65100),
            simMarker = Color(0xFFE53935),
            realLocationHalo = Color(0x332196F3),
            realLocationCore = Color(0xFF1976D2),
            statusRecording = Color(0xFFE53935),
            statusPaused = Color(0xFF9E9E9E),
            waypointLabel = Color(0xFF1B6FE3)
        )

        /**
         * 暗色地图（卫星图夜间 / 深色瓦片）下的覆盖色：
         * 提高明度，保证在深色瓦片上的可读性。
         */
        val Dark = MapPalette(
            routeLine = Color(0xFF7FB0FF),
            recordLine = Color(0xFF5EFC82),
            bookmarkFill = Color(0xFFFFD54F),
            bookmarkCore = Color(0xFFFFAB40),
            simMarker = Color(0xFFFF8A80),
            realLocationHalo = Color(0x4D82B1FF),
            realLocationCore = Color(0xFF82B1FF),
            statusRecording = Color(0xFFFF8A80),
            statusPaused = Color(0xFFBDBDBD),
            waypointLabel = Color(0xFF7FB0FF)
        )

        fun forDark(dark: Boolean): MapPalette = if (dark) Dark else Light
    }
}

/**
 * 来源可用性徽章色（瓦片源列表的「国内直连 / 需代理」标记）。
 *
 * 旧版在列表项里硬编码 0xFF2E7D32 / 0xFFE65100，无法跟随暗色主题，
 * 且在 Material 3 体系里属于游离色。收敛为语义角色后，
 * 明暗两套由 [BadgePalette.forDark] 选择。
 */
data class BadgePalette(
    val directContainer: Color,  // 国内直连徽章底色（已含透明度）
    val directContent: Color,    // 国内直连徽章文字
    val proxyContainer: Color,   // 需代理徽章底色（已含透明度）
    val proxyContent: Color      // 需代理徽章文字
) {
    companion object {
        val Light = BadgePalette(
            directContainer = Color(0xFF1E8E3E).copy(alpha = 0.12f),
            directContent = Color(0xFF17692F),
            proxyContainer = Color(0xFFE8710A).copy(alpha = 0.12f),
            proxyContent = Color(0xFFA04E00)
        )

        /** 暗色下提高明度，保证 10sp 小字在深色面板上的对比度。 */
        val Dark = BadgePalette(
            directContainer = Color(0xFF5DD39C).copy(alpha = 0.18f),
            directContent = Color(0xFF8CE8B4),
            proxyContainer = Color(0xFFFFB870).copy(alpha = 0.18f),
            proxyContent = Color(0xFFFFD3A6)
        )

        fun forDark(dark: Boolean): BadgePalette = if (dark) Dark else Light
    }
}

/**
 * 快捷功能圆形色板（Firefox 快捷方式风格）。
 *
 * 设计参考 Firefox 新建标签页的「快捷方式」：每个功能是一个**独立的实色圆盘**
 * （favicon 或字母磁贴），高饱和色 + 白色字形，保证在卫星图/矢量图等复杂
 * 底图上的可辨识性；标签文字放在圆盘下方，与圆盘共同构成一个功能单元。
 *
 * 选用原则：
 * - 每个功能一个独立色相，相邻功能色相拉开距离（避免混淆）；
 * - 颜色不随明暗主题切换——实色圆盘 + 白字形在深浅底图上都成立，
 *   主题相关的是面板 Surface 与标签文字（由 MaterialTheme 负责）；
 * - 成对功能（放大/缩小）共用色相，表明同属一个分组。
 */
object QuickFunctionColors {
    val Zoom = Color(0xFF64748B)      // 石板灰：放大 / 缩小（成对分组）
    val Layers = Color(0xFF2563EB)    // 蓝：切换地图图层
    val Bookmark = Color(0xFFF59E0B)  // 琥珀：地点收藏
    val Search = Color(0xFF7C3AED)    // 紫：搜索地点
    val Locate = Color(0xFF16A34A)    // 绿：定位与目标位置
    val Follow = Color(0xFF0D9488)    // 青绿：模拟移动跟随（开关）
    val Record = Color(0xFFDC2626)    // 红：录制真实轨迹（开关）
    val Diagnose = Color(0xFFEA580C)  // 橙：定位诊断
    val Mark = Color(0xFFDB2777)      // 玫红：地图标记模式（开关）
    val SimPoint = Color(0xFF0284C7)  // 天蓝：移动到模拟点
    val RealAsSim = Color(0xFF65A30D) // 草绿：将真实位置设为模拟点
}
