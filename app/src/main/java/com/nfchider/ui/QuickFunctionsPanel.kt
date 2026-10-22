package com.nfchider.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 快捷功能项：一个功能 = 一个独立圆形 + 下方标签（Firefox 快捷方式风格）。
 *
 * @param icon 圆形内的白色字形
 * @param label 圆下方的功能名（1-2 行）
 * @param container 圆盘实色（来自 [com.nfchider.ui.theme.QuickFunctionColors]）
 * @param active 开关类功能的激活态：圆盘外圈白色描边
 * @param enabled 禁用态：整体降透明度且不可点击
 */
data class QuickFunction(
    val icon: ImageVector,
    val label: String,
    val container: Color,
    val active: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

/**
 * 「快捷功能」面板——学习 Firefox 新建标签页的快捷方式布局：
 *
 * - 每个功能是一个**独立的实色圆盘**（高饱和色 + 白色字形），复杂地图底图上
 *   依然清晰可辨；圆盘下方跟一个小号标签，两者构成一个功能单元；
 * - 4 列网格排布，默认展示前 [coreCount] 个核心功能，其余收入
 *   「显示全部」，与 Firefox 的「显示全部」折叠行为一致；
 * - 面板可整体折叠为标题药丸，把地图可视面积还给用户；
 * - 开关类功能（跟随 / 标记 / 录制）用白色描边表达激活态。
 *
 * @param functions 全部功能，顺序即展示顺序（前 coreCount 个常驻）
 * @param open 面板是否展开（false 时仅显示标题药丸）
 * @param showAll 是否展示核心功能之外的功能
 */
@Composable
fun QuickFunctionsPanel(
    functions: List<QuickFunction>,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    showAll: Boolean,
    onShowAllChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    coreCount: Int = 8
) {
    Surface(
        modifier = modifier.width(260.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            // 面板标题行：整行可点击，折叠/展开面板
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onOpenChange(!open) }
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    .semantics {
                        contentDescription = if (open) "折叠快捷功能面板" else "展开快捷功能面板"
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "快捷功能",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(visible = open) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(6.dp))

                    // 核心功能网格（4 列）
                    functions.take(coreCount).chunked(4).forEach { row ->
                        FunctionRow(row)
                    }

                    // 「显示全部」折叠器（Firefox 快捷方式同款行为）
                    if (functions.size > coreCount) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onShowAllChange(!showAll) }
                                .padding(vertical = 5.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (showAll) "收起" else "显示全部",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                imageVector = if (showAll) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(16.dp)
                                    .padding(start = 2.dp)
                            )
                        }

                        AnimatedVisibility(visible = showAll) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                functions.drop(coreCount).chunked(4).forEach { row ->
                                    FunctionRow(row)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 一行功能圆（不足 4 个时左侧对齐，保持网格节奏）。 */
@Composable
private fun FunctionRow(row: List<QuickFunction>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        row.forEach { fn -> FunctionCircle(fn) }
    }
}

/**
 * 单个功能圆：实色圆盘 + 白色字形 + 下方标签。
 *
 * - 激活态（开关类功能）：圆盘外缘 2.5dp 白色描边，不改变圆盘本色；
 * - 禁用态：圆盘与标签同步降至 38% 透明度；
 * - 圆盘自带 contentDescription，标签文本从无障碍树隐藏，避免朗读重复。
 */
@Composable
private fun FunctionCircle(fn: QuickFunction, size: Dp = 48.dp) {
    val contentAlpha by animateFloatAsState(
        targetValue = if (fn.enabled) 1f else 0.38f,
        label = "functionAlpha"
    )
    Column(
        modifier = Modifier.width(56.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(fn.container.copy(alpha = contentAlpha))
                .then(
                    if (fn.active) {
                        Modifier.border(2.5.dp, Color.White, CircleShape)
                    } else {
                        Modifier
                    }
                )
                .clickable(enabled = fn.enabled) { fn.onClick() }
                .semantics { contentDescription = fn.label },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = fn.icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = contentAlpha),
                modifier = Modifier.size(size * 0.44f)
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = fn.label,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}
