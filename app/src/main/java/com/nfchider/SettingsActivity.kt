package com.nfchider

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.nfchider.location.SimConfigRepository
import com.nfchider.location.TrajectoryConfig
import com.nfchider.ui.theme.NfcHiderTheme

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NfcHiderTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    var config by remember { mutableStateOf(SimConfigRepository.load(context)) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        config = SimConfigRepository.load(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NFC Hider") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            GlobalHookSwitchCard(
                enabled = config.enabled,
                onToggle = { newEnabled ->
                    val updated = config.toBuilder().enabled(newEnabled).build()
                    config = updated
                    SimConfigRepository.save(context, updated)
                    Toast.makeText(
                        context,
                        if (newEnabled) "Hook 已开启" else "Hook 已关闭",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
            ModuleStatusCard()
            LocationSimCard(config)
            HookCategoriesCard()
            TargetAppCard()
            AboutCard()
        }
    }
}

@Composable
fun GlobalHookSwitchCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Hook 总开关",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (enabled) "● 已开启 (已勾选的应用生效中)" else "○ 已关闭 (已暂停所有拦截与模拟)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (enabled)
                    "已激活 Hook。对 LSPosed 作用域中已勾选的每个应用单独生效；在 App 内关闭此开关即可立即停止 Hook，无需重启应用。"
                else
                    "已暂停拦截与模拟。已勾选的应用恢复真实系统状态；随时打开此开关即可重新启用 Hook。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ModuleStatusCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "模块状态",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "工作模式：",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "按应用 Hook (在 LSPosed 勾选目标 App)",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "作用域：逐应用生效 · 支持 App 内实时启闭",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "版本：${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun LocationSimCard(config: TrajectoryConfig) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "位置模拟与地点收藏",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (config.enabled) {
                    Text(
                        text = "模拟中",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "支持 Google 卫星高清图层、地点收藏夹、真实移动轨迹现场录制回放与自定义路线规划。已勾选的应用将自动获取模拟定位。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    context.startActivity(Intent(context, MapActivity::class.java))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("打开卫星地图轨迹模拟与地点收藏")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(context, DexSqlActivity::class.java))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("DexSQL：用 SQL 查询目标 App 的 dex")
            }
        }
    }
}

@Composable
fun HookCategoriesCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "拦截分类",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            CategoryItem("应用定位查询 (LocationManager)", "拦截该应用内的 GPS/网络/Fused 定位查询与更新")
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            CategoryItem("Google 融合定位 (FusedLocationProviderClient)", "重写 Play 服务融合定位结果")
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            CategoryItem("包管理器 (PackageManager)", "拦截 NFC 硬件特性查询，隐藏 NFC 硬件")
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            CategoryItem("NFC 适配器 (NfcAdapter / NfcManager)", "拦截硬件适配器接口调用")
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            CategoryItem("反模拟定位检测 (Mock Provider Bypass)", "剥除 Mock 标识，各应用均识别为真实硬件定位")
        }
    }
}

@Composable
private fun CategoryItem(title: String, description: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun TargetAppCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "作用域设置指引",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "在 LSPosed 中勾选需要生效的应用",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "本模块按应用逐个生效：在 LSPosed 作用域中勾选目标 App（可勾选多个），重启目标 App 后对其生效。在 App 内关闭开关即停用 Hook，开启即可重新生效，无需重启。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun AboutCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "关于",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "基于现代 libxposed 的应用级模块。支持对选定应用隐藏 NFC 硬件、Google 卫星混合地图轨迹 GPS 位置模拟、真实物理航迹录制与地点收藏夹功能。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
