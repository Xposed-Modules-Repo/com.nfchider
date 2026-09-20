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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
            ModuleStatusCard()
            LocationSimCard()
            HookCategoriesCard()
            TargetAppCard()
            AboutCard()
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
                    text = "运行状态：",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "已就绪 (LSPosed 模块)",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "提示：在 LSPosed 中仅勾选需要生效的目标应用即可，模块自身无需勾选。",
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
fun LocationSimCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "位置模拟",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "在地图上绘制轨迹，让勾选的应用看到沿轨迹匀速移动的 GPS 位置。支持循环 / 往返 / 单次播放、速度调节与 GPX 导入。",
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
                Text("打开地图轨迹模拟")
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

            CategoryItem("包管理器 (PackageManager)", "拦截 NFC 软件包与系统特性查询")
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            CategoryItem("NFC 适配器 (NfcAdapter / NfcManager)", "拦截 NFC 硬件 API 访问")
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            CategoryItem("文件系统 (Filesystem)", "拦截 NFC 设备节点与驱动文件访问")
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            CategoryItem("Shell 进程 (Shell / Process)", "拦截 NFC 底层 Shell 命令执行")
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            CategoryItem("系统设置 (Settings)", "拦截 NFC 系统设置项读取与查询")
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
                text = "目标应用",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "请在 LSPosed / Xposed 管理器中配置作用域",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "模块会自动对在作用域中勾选的所有应用生效。",
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
                text = "在系统框架层 Hook 拦截 NFC 相关 Android API，防止目标应用检测到设备的 NFC 硬件。同时支持模拟自定义地图轨迹的 GPS 移动位置。请在 LSPosed 作用域中勾选需要生效的目标应用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
