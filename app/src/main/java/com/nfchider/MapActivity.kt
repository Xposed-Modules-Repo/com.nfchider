package com.nfchider

import android.Manifest
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nfchider.location.LocationSelfTest
import com.nfchider.location.ModuleService
import com.nfchider.location.RealLocationTracker
import com.nfchider.location.SimConfigRepository
import com.nfchider.location.TrajectoryConfig
import com.nfchider.location.TrajectoryEngine
import com.nfchider.ui.theme.NfcHiderTheme
import kotlinx.coroutines.delay
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MapActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NfcHiderTheme {
                LocationSimScreen()
            }
        }
    }
}

@Composable
fun LocationSimScreen() {
    val context = LocalContext.current
    var config by remember { mutableStateOf(SimConfigRepository.load(context)) }
    var markMode by remember { mutableStateOf(true) }
    var follow by remember { mutableStateOf(false) }
    var simPos by remember { mutableStateOf<TrajectoryEngine.Position?>(null) }
    var channelBound by remember { mutableStateOf(ModuleService.bound) }

    // ------------------------------------------------------------------
    // 真实定位与轨迹录制服务
    // ------------------------------------------------------------------
    val tracker = remember { RealLocationTracker(context) }
    val realLocation by tracker.currentLocation.collectAsState()
    val recordingState by tracker.recordingState.collectAsState()
    val recordedPoints by tracker.recordedPoints.collectAsState()
    val recordedDistanceM by tracker.recordedDistanceM.collectAsState()
    val currentSpeedMps by tracker.currentSpeedMps.collectAsState()
    val recordingDurationSec by tracker.recordingDurationSec.collectAsState()

    var hasCenteredToRealLocation by remember { mutableStateOf(false) }
    var showRecordingFinishDialog by remember { mutableStateOf(false) }
    var completedTrackPoints by remember { mutableStateOf<List<TrajectoryConfig.Point>>(emptyList()) }
    var showCancelRecordingConfirm by remember { mutableStateOf(false) }

    var showDiagnosticDialog by remember { mutableStateOf(false) }
    var diagnosticReport by remember { mutableStateOf(LocationSelfTest.runDiagnostics(context, config)) }

    val locationPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (tracker.hasLocationPermission()) {
            tracker.startLocationUpdates()
        }
        diagnosticReport = LocationSelfTest.runDiagnostics(context, config)
    }

    // 首次进入界面：自动检查权限并启动真实定位监听
    LaunchedEffect(Unit) {
        if (!tracker.hasLocationPermission()) {
            permissionLauncher.launch(locationPermissions)
        } else {
            tracker.startLocationUpdates()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            tracker.stopLocationUpdates()
        }
    }

    LaunchedEffect(config.enabled, config.points.size, channelBound) {
        diagnosticReport = LocationSelfTest.runDiagnostics(context, config)
    }

    val update: ((TrajectoryConfig.Builder) -> Unit) -> Unit = { transform ->
        val builder = config.toBuilder()
        transform(builder)
        config = builder.build()
        SimConfigRepository.save(context, config)
        diagnosticReport = LocationSelfTest.runDiagnostics(context, config)
    }

    // ------------------------------------------------------------------
    // GPX 导出文件 Launcher
    // ------------------------------------------------------------------
    val exportGpxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { os ->
                val gpxData = RealLocationTracker.generateGpx(completedTrackPoints)
                os.write(gpxData.toByteArray(Charsets.UTF_8))
                os.flush()
            }
            Toast.makeText(context, "GPX 轨迹文件已成功保存", Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(context, "导出失败: ${it.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------
    // 地图与图层
    // ------------------------------------------------------------------

    val tapHandler = remember { mutableStateOf<(GeoPoint) -> Unit>({}) }

    // 模拟路线折线（天蓝色）
    val routeLine = remember {
        Polyline().apply {
            outlinePaint.color = Color.parseColor("#3F8CFF")
            outlinePaint.strokeWidth = 9f
        }
    }

    // 真实录制航迹折线（亮翠绿色）
    val recordRouteLine = remember {
        Polyline().apply {
            outlinePaint.color = Color.parseColor("#00C853")
            outlinePaint.strokeWidth = 10f
        }
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(14.5)
            controller.setCenter(
                GeoPoint(
                    config.points.firstOrNull()?.lat ?: 39.9087,
                    config.points.firstOrNull()?.lng ?: 116.3975
                )
            )
            overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                    p?.let { tapHandler.value(it) }
                    return false
                }

                override fun longPressHelper(p: GeoPoint?): Boolean = true
            }))
            overlays.add(routeLine)
            overlays.add(recordRouteLine)
        }
    }

    // 模拟位置指示器（默认红色图标 Marker）
    val posMarker = remember {
        Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "模拟当前位置"
            isEnabled = false
        }.also { mapView.overlays.add(it) }
    }

    // 我的真实物理位置标记（蓝色光晕圆点）
    val myLocationMarker = remember {
        Marker(mapView).apply {
            icon = createMyLocationDrawable(context)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = "我的真实位置"
            isEnabled = false
        }.also { mapView.overlays.add(it) }
    }

    tapHandler.value = { geo ->
        if (recordingState != RealLocationTracker.RecordingState.IDLE) {
            Toast.makeText(context, "正在录制真实轨迹中，无法手动标记点", Toast.LENGTH_SHORT).show()
        } else if (markMode && !config.enabled) {
            update { it.points().add(TrajectoryConfig.Point(geo.latitude, geo.longitude, Double.NaN)) }
        }
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose { mapView.onPause() }
    }

    // 收到真实物理定位时：更新我的位置蓝点；初次定位且无模拟点时自动居中到真实位置
    LaunchedEffect(realLocation) {
        val loc = realLocation
        if (loc != null) {
            myLocationMarker.isEnabled = true
            myLocationMarker.position = GeoPoint(loc.latitude, loc.longitude)
            if (!hasCenteredToRealLocation && config.points.isEmpty()) {
                hasCenteredToRealLocation = true
                mapView.controller.setZoom(15.5)
                mapView.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
            }
        } else {
            myLocationMarker.isEnabled = false
        }
        mapView.invalidate()
    }

    // 录制航迹点变化时：刷新录制折线
    LaunchedEffect(recordedPoints) {
        val geoList = recordedPoints.map { GeoPoint(it.lat, it.lng) }
        recordRouteLine.setPoints(geoList)
        mapView.invalidate()
    }

    // 模拟配置轨迹点列表变更时：重绘路线与航点标记
    LaunchedEffect(config.points.size) {
        val overlays = mapView.overlays
        overlays.removeAll { it is Marker && it !== posMarker && it !== myLocationMarker }
        val geoPoints = config.points.map { GeoPoint(it.lat, it.lng) }
        routeLine.setPoints(geoPoints)
        config.points.forEachIndexed { index, _ ->
            overlays.add(
                Marker(mapView).apply {
                    position = geoPoints[index]
                    title = "点 ${index + 1}"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
            )
        }
        mapView.invalidate()
    }

    // 模拟位置实时预览心跳
    LaunchedEffect(config.enabled, config.startAt, config.speedMps, config.mode, config.points.size) {
        if (!config.enabled) {
            simPos = null
            return@LaunchedEffect
        }
        while (true) {
            simPos = TrajectoryEngine.positionAt(config, System.currentTimeMillis())
            delay(500)
        }
    }

    LaunchedEffect(simPos) {
        val p = simPos
        if (p == null) {
            posMarker.isEnabled = false
        } else {
            posMarker.isEnabled = true
            posMarker.position = GeoPoint(p.lat, p.lng)
            if (follow) mapView.controller.animateTo(GeoPoint(p.lat, p.lng))
        }
        mapView.invalidate()
    }

    // 配置通道状态轮询
    LaunchedEffect(Unit) {
        while (true) {
            channelBound = ModuleService.bound
            delay(2000)
        }
    }

    // ------------------------------------------------------------------
    // GPX 导入
    // ------------------------------------------------------------------
    val gpxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val points = runCatching {
            parseGpx(context.contentResolver.openInputStream(uri))
        }.getOrDefault(emptyList())
        if (points.isNotEmpty()) {
            update { b ->
                b.points().clear()
                b.points().addAll(points)
            }
            Toast.makeText(context, "已导入 ${points.size} 个轨迹点", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "GPX 中未找到轨迹点", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------
    // 布局与界面组件
    // ------------------------------------------------------------------

    val totalM = TrajectoryEngine.totalDistanceM(config)
    val etaMin = if (config.speedMps > 0) (totalM / config.speedMps / 60).roundToInt() else 0
    var sliderSpeed by remember(config.speedMps) { mutableStateOf(config.speedMps.toFloat()) }

    fun commitSpeed(newSpeed: Double) {
        val now = System.currentTimeMillis()
        val covered = TrajectoryEngine.positionAt(config, now).distanceM
        update { b ->
            b.speedMps(newSpeed)
            if (config.enabled) {
                b.startAt(now - ((covered / newSpeed) * 1000).toLong())
            }
        }
    }

    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

            if (config.points.isEmpty() && recordingState == RealLocationTracker.RecordingState.IDLE) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f)
                ) {
                    Text(
                        text = "点击右上角「定位」直接回到当前位置；\n开启「标记」点击地图添加点，或点击下方「录制轨迹」现场采集路线。",
                        modifier = Modifier.padding(14.dp),
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            // --------------------------------------------------------------
            // 右侧浮动快捷按钮组（缩放、定位到当前位置、跟随模拟位置）
            // --------------------------------------------------------------
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MapFab(icon = Icons.Filled.Add) { mapView.controller.zoomIn() }
                MapFabText(label = "-") { mapView.controller.zoomOut() }

                // 定位到我的物理位置
                MapFab(
                    icon = Icons.Filled.LocationOn,
                    tint = if (realLocation != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    if (!tracker.hasLocationPermission()) {
                        permissionLauncher.launch(locationPermissions)
                    } else if (!tracker.isLocationProviderEnabled()) {
                        Toast.makeText(context, "请在手机系统设置中开启定位服务 (GPS)", Toast.LENGTH_LONG).show()
                    } else {
                        val loc = realLocation
                        if (loc != null) {
                            mapView.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
                            if (mapView.zoomLevelDouble < 15.0) {
                                mapView.controller.setZoom(16.0)
                            }
                            Toast.makeText(context, "已定位到当前真实位置", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "正在获取真实定位，请稍候...", Toast.LENGTH_SHORT).show()
                            tracker.startLocationUpdates()
                        }
                    }
                }

                // 跟随模拟位置
                MapFab(
                    icon = Icons.Filled.Place,
                    tint = if (follow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                ) {
                    follow = !follow
                    if (follow) {
                        val p = simPos
                        val first = config.points.firstOrNull()
                        mapView.controller.animateTo(
                            GeoPoint(p?.lat ?: first?.lat ?: 39.9087, p?.lng ?: first?.lng ?: 116.3975)
                        )
                    }
                }
            }

            // --------------------------------------------------------------
            // 底部控制面板（模拟控制 / 实时轨迹录制控制）
            // --------------------------------------------------------------
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (recordingState != RealLocationTracker.RecordingState.IDLE) {
                        // ======================================================
                        // 轨迹录制中操作面板
                        // ======================================================
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    modifier = Modifier.size(10.dp),
                                    shape = CircleShape,
                                    color = if (recordingState == RealLocationTracker.RecordingState.RECORDING) androidx.compose.ui.graphics.Color(0xFFE53935) else androidx.compose.ui.graphics.Color(0xFF9E9E9E)
                                ) {}
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (recordingState == RealLocationTracker.RecordingState.RECORDING) "正在录制真实轨迹..." else "录制已暂停",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (recordingState == RealLocationTracker.RecordingState.RECORDING) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "用时 ${recordingDurationSec / 60}分${recordingDurationSec % 60}秒",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // 录制实时数据仪表
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp, horizontal = 12.dp),
                                horizontalArrangement = Arrangement.SpaceAround,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("采集航点", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${recordedPoints.size}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("记录里程", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(formatDistance(recordedDistanceM), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("实时时速", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${(currentSpeedMps * 3.6).roundToInt()} km/h", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                }
                            }
                        }

                        // 录制按钮控制组
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (recordingState == RealLocationTracker.RecordingState.RECORDING) {
                                OutlinedButton(
                                    onClick = { tracker.pauseRecording() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("暂停录制")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { tracker.resumeRecording() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("继续录制")
                                }
                            }

                            Button(
                                onClick = {
                                    val pts = tracker.finishRecording()
                                    if (pts.size < 2) {
                                        Toast.makeText(context, "采集航点少于 2 个，未生成有效轨迹", Toast.LENGTH_SHORT).show()
                                    } else {
                                        completedTrackPoints = pts
                                        showRecordingFinishDialog = true
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.weight(1.2f)
                            ) {
                                Text("完成录制")
                            }

                            TextButton(
                                onClick = { showCancelRecordingConfirm = true }
                            ) {
                                Text("放弃", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    } else {
                        // ======================================================
                        // 普通模拟状态面板
                        // ======================================================
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = if (config.enabled) "模拟运行中" else "未运行",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (config.enabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${config.points.size} 个点 · ${formatDistance(totalM)} · 单程约 ${etaMin} 分钟",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = if (channelBound) "配置通道：远程偏好" else "配置通道：文件兜底",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // 模拟运行状态与广播实时查看条
                        val report = diagnosticReport
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showDiagnosticDialog = true },
                            shape = RoundedCornerShape(8.dp),
                            color = when (report.status) {
                                LocationSelfTest.TestStatus.BROADCASTING -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                                LocationSelfTest.TestStatus.ROUTE_NOT_READY -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f)
                                LocationSelfTest.TestStatus.SIM_DISABLED -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = when (report.status) {
                                        LocationSelfTest.TestStatus.BROADCASTING -> Icons.Filled.CheckCircle
                                        LocationSelfTest.TestStatus.ROUTE_NOT_READY -> Icons.Filled.Warning
                                        LocationSelfTest.TestStatus.SIM_DISABLED -> Icons.Filled.Info
                                    },
                                    contentDescription = null,
                                    tint = when (report.status) {
                                        LocationSelfTest.TestStatus.BROADCASTING -> MaterialTheme.colorScheme.primary
                                        LocationSelfTest.TestStatus.ROUTE_NOT_READY -> MaterialTheme.colorScheme.secondary
                                        LocationSelfTest.TestStatus.SIM_DISABLED -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "模拟状态：${report.summaryText}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    val liveText = if (config.enabled && simPos != null) {
                                        "广播中: ${String.format(Locale.US, "%.5f, %.5f", simPos!!.lat, simPos!!.lng)} | ${String.format(Locale.US, "%.1f", simPos!!.speedMps)} m/s"
                                    } else {
                                        report.detailText
                                    }
                                    Text(
                                        text = liveText,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "查看详情 >",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // 速度控制
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "速度", style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.width(12.dp))
                            Slider(
                                value = sliderSpeed,
                                onValueChange = { sliderSpeed = it },
                                onValueChangeFinished = { commitSpeed(sliderSpeed.toDouble()) },
                                valueRange = 0.3f..90f,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${(config.speedMps * 3.6).roundToInt()} km/h",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // 速度预设
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("步行" to 5, "骑行" to 15, "驾车" to 40, "高铁" to 300).forEach { (label, kmh) ->
                                FilterChip(
                                    selected = (config.speedMps * 3.6).roundToInt() == kmh,
                                    onClick = { commitSpeed(kmh / 3.6) },
                                    label = { Text("$label $kmh") }
                                )
                            }
                        }

                        // 播放模式
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            val modes = listOf(
                                TrajectoryConfig.MODE_LOOP to "循环",
                                TrajectoryConfig.MODE_PINGPONG to "往返",
                                TrajectoryConfig.MODE_ONCE to "单次"
                            )
                            modes.forEachIndexed { index, (mode, label) ->
                                SegmentedButton(
                                    selected = config.mode == mode,
                                    onClick = { update { it.mode(mode) } },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size)
                                ) {
                                    Text(label)
                                }
                            }
                        }

                        // 编辑操作与录制入口
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = markMode,
                                onClick = { markMode = !markMode },
                                enabled = !config.enabled,
                                label = { Text(if (markMode) "标记 开" else "标记 关") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.LocationOn, contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                            AssistChip(
                                onClick = {
                                    update { b ->
                                        if (b.points().isNotEmpty()) b.points().removeAt(b.points().size - 1)
                                    }
                                },
                                enabled = config.points.isNotEmpty() && !config.enabled,
                                label = { Text("撤销") }
                            )
                            AssistChip(
                                onClick = { update { b -> b.points().clear(); b.enabled(false) } },
                                enabled = config.points.isNotEmpty(),
                                label = { Text("清空") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Clear, contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )

                            // 录制真实轨迹按钮
                            Button(
                                onClick = {
                                    if (config.enabled) {
                                        Toast.makeText(context, "请先停止模拟定位后再录制真实轨迹", Toast.LENGTH_SHORT).show()
                                    } else if (!tracker.hasLocationPermission()) {
                                        permissionLauncher.launch(locationPermissions)
                                    } else if (!tracker.isLocationProviderEnabled()) {
                                        Toast.makeText(context, "请在手机系统设置中开启定位服务 (GPS)", Toast.LENGTH_LONG).show()
                                    } else {
                                        tracker.startRecording()
                                        Toast.makeText(context, "开始录制路线，带着手机移动即可", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Surface(
                                    modifier = Modifier.size(8.dp),
                                    shape = CircleShape,
                                    color = androidx.compose.ui.graphics.Color(0xFFE53935)
                                ) {}
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("录制轨迹", fontSize = 12.sp)
                            }

                            Spacer(modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = { gpxLauncher.launch(arrayOf("*/*")) }) {
                                Text("导入 GPX")
                            }
                        }

                        // 模拟启动 / 停止
                        if (config.enabled) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedButton(
                                    onClick = { update { it.startAt(System.currentTimeMillis()) } },
                                    modifier = Modifier.weight(1f)
                                ) { Text("重新开始") }
                                Button(
                                    onClick = { update { it.enabled(false) } },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    ),
                                    modifier = Modifier.weight(1f)
                                ) { Text("停止模拟") }
                            }
                        } else {
                            Button(
                                onClick = { update { it.enabled(true).startAt(System.currentTimeMillis()) } },
                                enabled = config.points.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("开始模拟") }
                        }

                        Text(
                            text = "提示：需在 LSPosed 勾选目标应用并重启目标应用；录制真实轨迹可一键转为模拟路线。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // --------------------------------------------------------------
            // 录制完成操作对话框
            // --------------------------------------------------------------
            if (showRecordingFinishDialog) {
                AlertDialog(
                    onDismissRequest = { showRecordingFinishDialog = false },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("轨迹录制已完成")
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("共采集到 ${completedTrackPoints.size} 个有效路线航点。")
                            Text("录制总里程：${formatDistance(recordedDistanceM)}")
                            Text("耗时：${recordingDurationSec / 60}分${recordingDurationSec % 60}秒")
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "您可以直接将这段真实路线设为模拟轨迹，或者导出为标准 GPX 文件分享保存。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                update { b ->
                                    b.points().clear()
                                    b.points().addAll(completedTrackPoints)
                                }
                                showRecordingFinishDialog = false
                                Toast.makeText(context, "已成功设为模拟轨迹！", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("应用为模拟轨迹")
                        }
                    },
                    dismissButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val timeStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                                    exportGpxLauncher.launch("track_$timeStr.gpx")
                                }
                            ) {
                                Text("导出 GPX")
                            }
                            TextButton(onClick = { showRecordingFinishDialog = false }) {
                                Text("关闭")
                            }
                        }
                    }
                )
            }

            // 放弃录制确认对话框
            if (showCancelRecordingConfirm) {
                AlertDialog(
                    onDismissRequest = { showCancelRecordingConfirm = false },
                    title = { Text("放弃录制？") },
                    text = { Text("当前已采集的航点数据将被丢弃，确定放弃吗？") },
                    confirmButton = {
                        Button(
                            onClick = {
                                tracker.cancelRecording()
                                showCancelRecordingConfirm = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("放弃录制")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showCancelRecordingConfirm = false }) {
                            Text("继续录制")
                        }
                    }
                )
            }

            // 运行状态与 LSPosed 诊断对话框
            if (showDiagnosticDialog) {
                DiagnosticDialog(
                    report = diagnosticReport,
                    onDismiss = { showDiagnosticDialog = false },
                    onRefresh = {
                        if (!diagnosticReport.hasPermission) {
                            permissionLauncher.launch(locationPermissions)
                        } else {
                            diagnosticReport = LocationSelfTest.runDiagnostics(context, config)
                        }
                    }
                )
            }
        }
    }
}

/**
 * 动态纯代码生成高品质定位蓝点 Drawable（带半透明光晕与深蓝核心）
 */
private fun createMyLocationDrawable(context: Context): Drawable {
    val density = context.resources.displayMetrics.density
    val size = (24 * density).toInt().coerceAtLeast(24)
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val center = size / 2f
    val radius = size / 2f

    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    // 外层半透明天蓝光晕
    paint.color = Color.parseColor("#332196F3")
    canvas.drawCircle(center, center, radius, paint)

    // 中层白色边框
    paint.color = Color.WHITE
    canvas.drawCircle(center, center, radius * 0.62f, paint)

    // 内层核心深蓝圆点
    paint.color = Color.parseColor("#1976D2")
    canvas.drawCircle(center, center, radius * 0.45f, paint)

    return BitmapDrawable(context.resources, bitmap)
}

@Composable
private fun MapFab(icon: ImageVector, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        shadowElevation = 4.dp
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = null, tint = tint)
        }
    }
}

@Composable
private fun MapFabText(label: String, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        shadowElevation = 4.dp
    ) {
        IconButton(onClick = onClick) {
            Text(
                text = label,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun formatDistance(meters: Double): String = when {
    meters >= 1000 -> String.format(Locale.US, "%.1f km", meters / 1000)
    else -> "${meters.roundToInt()} m"
}

// ------------------------------------------------------------------
// GPX parsing
// ------------------------------------------------------------------

private fun parseGpx(input: InputStream?): List<TrajectoryConfig.Point> {
    if (input == null) return emptyList()
    val raw = mutableListOf<DoubleArray>() // [lat, lng, ele]
    try {
        val parser = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                when (parser.name) {
                    "trkpt", "rtept", "wpt" -> {
                        val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        val lng = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        if (lat != null && lng != null) {
                            raw.add(doubleArrayOf(lat, lng, Double.NaN))
                        }
                    }
                    "ele" -> {
                        val v = runCatching { parser.nextText().toDouble() }.getOrNull()
                        if (v != null && raw.isNotEmpty()) {
                            raw.last()[2] = v
                        }
                    }
                }
            }
            event = parser.next()
        }
    } catch (_: Throwable) {
        return emptyList()
    } finally {
        runCatching { input.close() }
    }

    // Cap the point count; dense GPX tracks are decimated evenly.
    val max = 2000
    if (raw.size <= max) return raw.map { TrajectoryConfig.Point(it[0], it[1], it[2]) }
    val step = raw.size.toDouble() / max
    val result = ArrayList<TrajectoryConfig.Point>(max + 1)
    var i = 0.0
    while (i < raw.size) {
        val p = raw[i.toInt()]
        result.add(TrajectoryConfig.Point(p[0], p[1], p[2]))
        i += step
    }
    raw.last().let { result.add(TrajectoryConfig.Point(it[0], it[1], it[2])) }
    return result
}

@Composable
fun DiagnosticDialog(
    report: LocationSelfTest.DiagnosticReport,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when (report.status) {
                        LocationSelfTest.TestStatus.BROADCASTING -> Icons.Filled.CheckCircle
                        LocationSelfTest.TestStatus.ROUTE_NOT_READY -> Icons.Filled.Warning
                        LocationSelfTest.TestStatus.SIM_DISABLED -> Icons.Filled.Info
                    },
                    contentDescription = null,
                    tint = when (report.status) {
                        LocationSelfTest.TestStatus.BROADCASTING -> MaterialTheme.colorScheme.primary
                        LocationSelfTest.TestStatus.ROUTE_NOT_READY -> MaterialTheme.colorScheme.secondary
                        LocationSelfTest.TestStatus.SIM_DISABLED -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("模拟运行状态与说明")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = when (report.status) {
                            LocationSelfTest.TestStatus.BROADCASTING -> MaterialTheme.colorScheme.primaryContainer
                            LocationSelfTest.TestStatus.ROUTE_NOT_READY -> MaterialTheme.colorScheme.secondaryContainer
                            LocationSelfTest.TestStatus.SIM_DISABLED -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = report.summaryText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = report.detailText,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Text("核心指标：", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)

                DiagnosticItem(
                    label = "模拟运行开关",
                    value = if (report.isSimEnabled) "已开启" else "未开启",
                    isOk = report.isSimEnabled
                )
                DiagnosticItem(
                    label = "轨迹就绪状态",
                    value = if (report.hasRoute) "已就绪" else "点数不足",
                    isOk = report.hasRoute
                )
                DiagnosticItem(
                    label = "配置同步通道",
                    value = if (report.isChannelBound) "LSPosed 远程通道" else "本地持久化就绪",
                    isOk = true
                )

                if (report.expectedPos != null) {
                    DiagnosticItem(
                        label = "当前广播坐标",
                        value = "${String.format(Locale.US, "%.5f, %.5f", report.expectedPos.lat, report.expectedPos.lng)}",
                        isOk = true
                    )
                }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "💡 LSPosed 作用域说明",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "1. 模块本身作为控制器，无需也不能在 LSPosed 中勾选。\n2. 请在 LSPosed 中勾选需要使用模拟位置的目标应用（如打卡软件、高德地图等）。\n3. 勾选后，在系统设置中对目标应用点击「强行停止」再重新打开，即可生效模拟定位！",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (report.actualLocation != null) {
                    Text(
                        text = "手机当前真实 GPS: ${String.format(Locale.US, "%.5f, %.5f", report.actualLocation.latitude, report.actualLocation.longitude)} (本模块未勾选自身，故返回真实 GPS；目标应用已被 Hook 替换为模拟坐标)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onRefresh) {
                Text(if (!report.hasPermission) "读取真实 GPS 对比" else "刷新状态")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun DiagnosticItem(label: String, value: String, isOk: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = if (isOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
}
