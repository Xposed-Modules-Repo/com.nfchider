package com.nfchider

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nfchider.location.DexSqlRunner
import com.nfchider.ui.theme.NfcHiderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DexSqlActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NfcHiderTheme {
                DexSqlScreen()
            }
        }
    }
}

private data class AppEntry(
    val label: String,
    val packageName: String,
    val sourceDir: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DexSqlScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val apps = remember {
        val pm = context.packageManager
        pm.getInstalledApplications(0)
            .map { info ->
                AppEntry(
                    label = pm.getApplicationLabel(info).toString(),
                    packageName = info.packageName,
                    sourceDir = info.sourceDir
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    var selected by remember { mutableStateOf<AppEntry?>(apps.firstOrNull()) }
    var showAppPicker by remember { mutableStateOf(false) }
    var sql by remember {
        mutableStateOf("SELECT class_name, name FROM methods LIMIT 20")
    }
    var useCache by remember { mutableStateOf(true) }
    var decodeSig by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("选择一个应用并点击「查询」开始。") }
    var running by remember { mutableStateOf(false) }

    val runDecode: (String) -> Unit = runDecode@{ sig ->
        val app = selected ?: return@runDecode
        running = true
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                DexSqlRunner.decode(context, app.sourceDir, sig)
            }
            result = out
            running = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DexSQL 查询") },
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
                .padding(16.dp)
        ) {
            // Target app selector
            OutlinedButton(
                onClick = { showAppPicker = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    selected?.let { "${it.label} (${it.packageName})" } ?: "选择目标应用"
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "APK: ${selected?.sourceDir ?: "—"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            // SQL input
            Text("SQL", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = sql,
                onValueChange = { sql = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                ),
                shape = RoundedCornerShape(8.dp)
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = useCache, onCheckedChange = { useCache = it })
                Text(
                    "使用缓存加速（首次解析后复用，重复查询更快）",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val app = selected ?: return@Button
                        val query = sql.trim()
                        if (query.isEmpty()) return@Button
                        running = true
                        scope.launch {
                            val out = withContext(Dispatchers.IO) {
                                if (useCache) {
                                    DexSqlRunner.queryWithCache(context, app.sourceDir, query)
                                } else {
                                    DexSqlRunner.query(context, app.sourceDir, query)
                                }
                            }
                            result = out
                            running = false
                        }
                    },
                    enabled = !running && selected != null
                ) {
                    Text(if (running) "运行中..." else "查询")
                }
                OutlinedButton(
                    onClick = {
                        val app = selected ?: return@OutlinedButton
                        running = true
                        scope.launch {
                            val out = withContext(Dispatchers.IO) {
                                DexSqlRunner.tables(context, app.sourceDir)
                            }
                            result = out
                            running = false
                        }
                    },
                    enabled = !running && selected != null
                ) {
                    Text("表结构")
                }
            }
            Spacer(Modifier.height(12.dp))

            // Decode section
            Text(
                "反汇编方法",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                value = decodeSig,
                onValueChange = { decodeSig = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Lcom/example/Foo;->bar(I)V") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = {
                    val sig = decodeSig.trim()
                    if (sig.isNotEmpty()) runDecode(sig)
                },
                enabled = !running && selected != null && decodeSig.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("反汇编")
            }
            Spacer(Modifier.height(12.dp))

            Text(
                "结果",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Text(
                    text = result,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                )
            }
        }
    }

    if (showAppPicker) {
        var filter by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAppPicker = false },
            title = { Text("选择目标应用") },
            text = {
                Column {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        label = { Text("搜索") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        val kw = filter.trim().lowercase()
                        apps.filter {
                            kw.isEmpty() ||
                                it.label.lowercase().contains(kw) ||
                                it.packageName.lowercase().contains(kw)
                        }.forEach { app ->
                            TextButton(
                                onClick = {
                                    selected = app
                                    showAppPicker = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                content = {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(app.label, fontWeight = FontWeight.Medium)
                                        Text(
                                            app.packageName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAppPicker = false }) { Text("取消") }
            }
        )
    }
}
