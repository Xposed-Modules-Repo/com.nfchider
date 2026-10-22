package com.nfchider.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * NFC Hider 品牌配色（Material 3 完整色彩角色）。
 *
 * 旧版仅覆写 primary/secondary/tertiary 三个角色，其余角色回落到
 * M3 默认紫色基线，与动态取色分支打架，导致非动态色设备上出现
 * 「紫色卡片 + 蓝色强调」的混搭。这里给出完整色板。
 */
private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = BrandBlueContainer,
    onPrimaryContainer = Color_OnPrimaryContainerLight,
    secondary = SlateSecondary,
    onSecondary = Color.White,
    secondaryContainer = SlateSecondaryContainer,
    onSecondaryContainer = Color_OnSecondaryContainerLight,
    tertiary = TealTertiary,
    onTertiary = Color.White,
    tertiaryContainer = TealTertiaryContainer,
    onTertiaryContainer = Color_OnTertiaryContainerLight,
    error = ErrorRed,
    onError = Color.White,
    errorContainer = ErrorRedContainer,
    onErrorContainer = Color_OnErrorContainerLight,
    background = NeutralSurfaceLight,
    onBackground = NeutralOnSurfaceLight,
    surface = NeutralSurfaceLight,
    onSurface = NeutralOnSurfaceLight,
    surfaceVariant = NeutralSurfaceVariantLight,
    onSurfaceVariant = NeutralOnSurfaceVariantLight,
    outline = NeutralOutlineLight,
    outlineVariant = NeutralOutlineVariantLight,
    inverseSurface = NeutralInverseSurfaceLight,
    inverseOnSurface = NeutralInverseOnSurfaceLight,
    inversePrimary = BrandBlueDark,
    surfaceTint = BrandBlue,
    surfaceContainerLowest = NeutralWhite,
    surfaceContainerLow = NeutralContainerLowLight,
    surfaceContainer = NeutralContainerLight,
    surfaceContainerHigh = NeutralContainerHighLight,
    surfaceContainerHighest = NeutralContainerHighestLight
)

private val DarkColors = darkColorScheme(
    primary = BrandBlueDark,
    onPrimary = Color_OnPrimaryDark,
    primaryContainer = BrandBlueContainerDark,
    onPrimaryContainer = BrandBlueContainer,
    secondary = SlateSecondaryDark,
    onSecondary = Color_OnSecondaryDark,
    secondaryContainer = SlateSecondaryContainerDark,
    onSecondaryContainer = SlateSecondaryContainer,
    tertiary = TealTertiaryDark,
    onTertiary = Color_OnTertiaryDark,
    tertiaryContainer = TealTertiaryContainerDark,
    onTertiaryContainer = TealTertiaryContainer,
    error = ErrorRedDark,
    onError = Color_OnErrorDark,
    errorContainer = ErrorRedContainerDark,
    onErrorContainer = ErrorRedContainer,
    background = NeutralSurfaceDark,
    onBackground = NeutralOnSurfaceDark,
    surface = NeutralSurfaceDark,
    onSurface = NeutralOnSurfaceDark,
    surfaceVariant = NeutralSurfaceVariantDark,
    onSurfaceVariant = NeutralOnSurfaceVariantDark,
    outline = NeutralOutlineDark,
    outlineVariant = NeutralOutlineVariantDark,
    inverseSurface = NeutralInverseSurfaceDark,
    inverseOnSurface = NeutralInverseOnSurfaceDark,
    inversePrimary = BrandBlue,
    surfaceTint = BrandBlueDark,
    surfaceContainerLowest = NeutralContainerLowestDark,
    surfaceContainerLow = NeutralContainerLowDark,
    surfaceContainer = NeutralContainerDark,
    surfaceContainerHigh = NeutralContainerHighDark,
    surfaceContainerHighest = NeutralContainerHighestDark
)

/**
 * 形状系统：工具类 App 使用中等圆角，面板使用大圆角，
 * 比 M3 默认（4/8/12/16/28 dp）更柔和一档，与地图浮层观感匹配。
 */
private val NfcHiderShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * 来源可用性徽章色（MapPalette 同款运行时选择，见 [BadgePalette]）。
 * 静态提供：色板本身不随重组变化，仅随暗/亮主题切换。
 */
val LocalBadgePalette = staticCompositionLocalOf { BadgePalette.Light }

@Composable
fun NfcHiderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 工具类 App 保持品牌一致性优先；需要跟随系统取色时传 true。
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(
        LocalBadgePalette provides BadgePalette.forDark(darkTheme)
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = NfcHiderTypography,
            shapes = NfcHiderShapes,
            content = content
        )
    }
}

/** 当前是否为暗色主题（供地图覆盖层选色）。 */
@Composable
fun isMapDark(): Boolean = isSystemInDarkTheme()
