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
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import com.nfchider.location.BookmarkRepository
import com.nfchider.location.GoogleHybridTileSource
import com.nfchider.location.AmapSatelliteTileSource
import com.nfchider.location.AmapVectorTileSource
import com.nfchider.location.LocationBookmark
import com.nfchider.location.LocationSearchService
import com.nfchider.location.MapDiagnostics
import com.nfchider.location.MapSourceItem
import com.nfchider.location.SearchResultPlace
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
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
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(SimConfigRepository.load(context)) }
    var markMode by remember { mutableStateOf(true) }
    var follow by remember { mutableStateOf(false) }
    var simPos by remember { mutableStateOf<TrajectoryEngine.Position?>(null) }
    var channelBound by remember { mutableStateOf(ModuleService.bound) }

    // 地图图层与诊断状态
    val mapPrefs = remember { context.getSharedPreferences("map_prefs", Context.MODE_PRIVATE) }
    var currentMapSource by remember {
        val savedKey = mapPrefs.getString("selected_map_source", MapSourceItem.GOOGLE_HYBRID.key)
            ?: MapSourceItem.GOOGLE_HYBRID.key
        mutableStateOf(MapSourceItem.fromKey(savedKey))
    }
    var showMapSourceDialog by remember { mutableStateOf(false) }
    var mapDiagnosticResult by remember { mutableStateOf<MapDiagnostics.DiagnosticResult?>(null) }
    var isMapAlertDismissed by remember { mutableStateOf(false) }
    var isPanelExpanded by remember { mutableStateOf(true) }

    var bookmarks by remember { mutableStateOf(BookmarkRepository.load(context)) }
    var showBookmarksDialog by remember { mutableStateOf(false) }
    var showAddBookmarkDialog by remember { mutableStateOf(false) }
    var targetBookmarkPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var bookmarkToEdit by remember { mutableStateOf<LocationBookmark?>(null) }
    var showLocationMenuDialog by remember { mutableStateOf(false) }
    var showSearchLocationDialog by remember { mutableStateOf(false) }

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
    var diagnosticReport by remember {
        mutableStateOf(LocationSelfTest.runDiagnostics(context, config, mapDiagnosticResult))
    }

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
        diagnosticReport = LocationSelfTest.runDiagnostics(context, config, mapDiagnosticResult)
    }

    // 首次进入界面：自动检查权限并启动真实定位监听与地图连通性诊断
    LaunchedEffect(Unit) {
        if (!tracker.hasLocationPermission()) {
            permissionLauncher.launch(locationPermissions)
        } else {
            tracker.startLocationUpdates()
        }
        mapDiagnosticResult = MapDiagnostics.diagnose(context, currentMapSource)
    }

    DisposableEffect(Unit) {
        onDispose {
            tracker.stopLocationUpdates()
        }
    }

    LaunchedEffect(currentMapSource) {
        mapDiagnosticResult = MapDiagnostics.diagnose(context, currentMapSource)
    }

    LaunchedEffect(config.enabled, config.points.size, channelBound, mapDiagnosticResult) {
        diagnosticReport = LocationSelfTest.runDiagnostics(context, config, mapDiagnosticResult)
    }

    val update: ((TrajectoryConfig.Builder) -> Unit) -> Unit = { transform ->
        val builder = config.toBuilder()
        transform(builder)
        config = builder.build()
        SimConfigRepository.save(context, config)
        diagnosticReport = LocationSelfTest.runDiagnostics(context, config, mapDiagnosticResult)
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
            setTileSource(currentMapSource.getTileSource())
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(15.5)
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

                override fun longPressHelper(p: GeoPoint?): Boolean {
                    if (p != null) {
                        targetBookmarkPoint = p
                        showAddBookmarkDialog = true
                    }
                    return true
                }
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

    // 地点收藏标记列表与路线航点标记列表
    val bookmarkMarkers = remember { mutableListOf<Marker>() }
    val waypointMarkers = remember { mutableListOf<Marker>() }
    val bookmarkDrawable = remember { createBookmarkDrawable(context) }

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
        waypointMarkers.forEach { overlays.remove(it) }
        waypointMarkers.clear()
        val geoPoints = config.points.map { GeoPoint(it.lat, it.lng) }
        routeLine.setPoints(geoPoints)
        config.points.forEachIndexed { index, _ ->
            val m = Marker(mapView).apply {
                position = geoPoints[index]
                title = "点 ${index + 1}"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            waypointMarkers.add(m)
            overlays.add(m)
        }
        mapView.invalidate()
    }

    // 收藏地点变更时：重绘收藏星标 Marker
    LaunchedEffect(bookmarks) {
        val overlays = mapView.overlays
        bookmarkMarkers.forEach { overlays.remove(it) }
        bookmarkMarkers.clear()
        bookmarks.forEach { bm ->
            val m = Marker(mapView).apply {
                position = GeoPoint(bm.lat, bm.lng)
                title = "⭐ " + bm.name
                snippet = "坐标: ${String.format(Locale.US, "%.5f, %.5f", bm.lat, bm.lng)}"
                icon = bookmarkDrawable
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setOnMarkerClickListener { marker, _ ->
                    marker.showInfoWindow()
                    true
                }
            }
            bookmarkMarkers.add(m)
            overlays.add(m)
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

            // --------------------------------------------------------------
            // 地图瓦片加载异常警告与一键修复浮条
            // --------------------------------------------------------------
            if (mapDiagnosticResult?.isDisplayAbnormal == true && !isMapAlertDismissed) {
                Card(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 12.dp, start = 12.dp, end = 76.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.95f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = mapDiagnosticResult?.issueTitle ?: "地图加载异常",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            IconButton(
                                onClick = { isMapAlertDismissed = true },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Clear,
                                    contentDescription = "关闭",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = mapDiagnosticResult?.issueReason ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            maxLines = 3
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (mapDiagnosticResult?.canSwitchToDomestic == true) {
                                Button(
                                    onClick = {
                                        val newSource = MapSourceItem.AMAP_VECTOR
                                        currentMapSource = newSource
                                        mapPrefs.edit().putString("selected_map_source", newSource.key).apply()
                                        mapView.setTileSource(newSource.getTileSource())
                                        mapView.invalidate()
                                        isMapAlertDismissed = true
                                        Toast.makeText(context, "已切换为国内高德地图，瓦片极速加载中", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    ),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text("⚡ 一键切换高德", fontSize = 11.sp)
                                }
                            }
                            OutlinedButton(
                                onClick = { showDiagnosticDialog = true },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text("排查指南", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            if (config.points.isEmpty() && recordingState == RealLocationTracker.RecordingState.IDLE) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f)
                ) {
                    Text(
                        text = "点击右上角「定位」直接回到当前位置；\n开启「标记」点击地图添加点，长按地图或点击「⭐」收藏地点；下方可现场「录制轨迹」。",
                        modifier = Modifier.padding(14.dp),
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            // --------------------------------------------------------------
            // 右侧浮动快捷按钮组（缩放、定位到当前位置、跟随模拟位置、图层与收藏）
            // --------------------------------------------------------------
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MapFab(icon = Icons.Filled.Add) { mapView.controller.zoomIn() }
                MapFabText(label = "-") { mapView.controller.zoomOut() }

                // 地图图层切换 (高德/Google/OSM)
                MapFabText(
                    label = if (currentMapSource == MapSourceItem.AMAP_SATELLITE || currentMapSource == MapSourceItem.GOOGLE_HYBRID) "🛰️" else "🗺️"
                ) {
                    showMapSourceDialog = true
                }

                // 地点收藏夹
                MapFab(
                    icon = Icons.Filled.Star,
                    tint = androidx.compose.ui.graphics.Color(0xFFFFB300)
                ) {
                    showBookmarksDialog = true
                }

                // 快捷地点搜索 (寻找/设置模拟位置)
                MapFab(
                    icon = Icons.Filled.Search,
                    tint = MaterialTheme.colorScheme.primary
                ) {
                    showSearchLocationDialog = true
                }

                // 定位与模拟位置二级菜单 (合并原先容易混淆的双Pin定位按钮)
                MapFab(
                    icon = Icons.Filled.LocationOn,
                    tint = if (follow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                ) {
                    showLocationMenuDialog = true
                }
            }

            // --------------------------------------------------------------
            // 底部控制面板（模拟控制 / 实时轨迹录制控制）
            // --------------------------------------------------------------
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                ) {
                    // 顶部拖动/切换把手
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isPanelExpanded = !isPanelExpanded }
                            .padding(top = 8.dp, bottom = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier
                                .width(36.dp)
                                .height(4.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        ) {}
                    }

                    if (!isPanelExpanded) {
                        // ==================================================
                        // 极简折叠状态（大幅腾出地图可视范围，避免挡住路线与标记）
                        // ==================================================
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 12.dp, top = 2.dp, bottom = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { isPanelExpanded = true }
                            ) {
                                Surface(
                                    modifier = Modifier.size(9.dp),
                                    shape = CircleShape,
                                    color = when {
                                        recordingState != RealLocationTracker.RecordingState.IDLE ->
                                            if (recordingState == RealLocationTracker.RecordingState.RECORDING)
                                                androidx.compose.ui.graphics.Color(0xFFE53935)
                                            else androidx.compose.ui.graphics.Color(0xFF9E9E9E)
                                        config.enabled -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    }
                                ) {}
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = when {
                                            recordingState != RealLocationTracker.RecordingState.IDLE ->
                                                "轨迹录制中 (${recordedPoints.size} 点 · ${(currentSpeedMps * 3.6).roundToInt()} km/h)"
                                            config.enabled ->
                                                "模拟运行中 · ${(config.speedMps * 3.6).roundToInt()} km/h"
                                            else ->
                                                "模拟已停止 (${config.points.size} 点)"
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (config.enabled || recordingState != RealLocationTracker.RecordingState.IDLE)
                                            MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                    val subText = if (config.enabled && simPos != null) {
                                        "广播: ${String.format(Locale.US, "%.5f, %.5f", simPos!!.lat, simPos!!.lng)}"
                                    } else if (config.points.isNotEmpty()) {
                                        "${config.points.size} 点 · ${formatDistance(totalM)} · 约 ${etaMin} 分钟"
                                    } else {
                                        "点击展开面板添加点或导入轨迹"
                                    }
                                    Text(
                                        text = subText,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (recordingState == RealLocationTracker.RecordingState.IDLE) {
                                    if (config.enabled) {
                                        Button(
                                            onClick = { update { it.enabled(false) } },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Text("停止", fontSize = 12.sp)
                                        }
                                    } else {
                                        Button(
                                            onClick = { update { it.enabled(true).startAt(System.currentTimeMillis()) } },
                                            enabled = config.points.isNotEmpty(),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Text("开始", fontSize = 12.sp)
                                        }
                                    }
                                }
                                OutlinedButton(
                                    onClick = { isPanelExpanded = true },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text("展开 ▲", fontSize = 11.sp)
                                }
                            }
                        }
                    } else {
                        // ==================================================
                        // 展开完整操作面板
                        // ==================================================
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
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
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "用时 ${recordingDurationSec / 60}分${recordingDurationSec % 60}秒",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        TextButton(
                                            onClick = { isPanelExpanded = false },
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 1.dp),
                                            modifier = Modifier.height(26.dp)
                                        ) {
                                            Text("收起 ▼", fontSize = 11.sp)
                                        }
                                    }
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (channelBound) "配置通道：远程偏好" else "配置通道：文件兜底",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                TextButton(
                                    onClick = { isPanelExpanded = false },
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 1.dp),
                                    modifier = Modifier.height(26.dp)
                                ) {
                                    Text("收起 ▼", fontSize = 11.sp)
                                }
                            }
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
                            AssistChip(
                                onClick = { showSearchLocationDialog = true },
                                label = { Text("搜点") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Search, contentDescription = null,
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

            // 运行状态与 LSPosed / 地图诊断对话框
            if (showDiagnosticDialog) {
                DiagnosticDialog(
                    report = diagnosticReport,
                    onDismiss = { showDiagnosticDialog = false },
                    onRefresh = {
                        if (!diagnosticReport.hasPermission) {
                            permissionLauncher.launch(locationPermissions)
                        } else {
                            scope.launch {
                                mapDiagnosticResult = MapDiagnostics.diagnose(context, currentMapSource)
                                diagnosticReport = LocationSelfTest.runDiagnostics(context, config, mapDiagnosticResult)
                            }
                        }
                    },
                    onSwitchToAmap = {
                        val newSource = MapSourceItem.AMAP_VECTOR
                        currentMapSource = newSource
                        mapPrefs.edit().putString("selected_map_source", newSource.key).apply()
                        mapView.setTileSource(newSource.getTileSource())
                        mapView.invalidate()
                        isMapAlertDismissed = true
                        showDiagnosticDialog = false
                        Toast.makeText(context, "已切换为国内高德地图", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // 地图图层切换对话框
            if (showMapSourceDialog) {
                MapSourceDialog(
                    currentSource = currentMapSource,
                    onDismiss = { showMapSourceDialog = false },
                    onSelectSource = { newSource ->
                        currentMapSource = newSource
                        mapPrefs.edit().putString("selected_map_source", newSource.key).apply()
                        mapView.setTileSource(newSource.getTileSource())
                        mapView.invalidate()
                        isMapAlertDismissed = false
                        Toast.makeText(context, "已切换为「${newSource.title}」", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // --------------------------------------------------------------
            // 地点收藏夹对话框与操作
            // --------------------------------------------------------------
            if (showBookmarksDialog) {
                BookmarksDialog(
                    bookmarks = bookmarks,
                    onDismiss = { showBookmarksDialog = false },
                    onNavigateTo = { bm ->
                        mapView.controller.animateTo(GeoPoint(bm.lat, bm.lng))
                        if (mapView.zoomLevelDouble < 16.0) {
                            mapView.controller.setZoom(16.5)
                        }
                        showBookmarksDialog = false
                    },
                    onAddToRoute = { bm ->
                        update { it.points().add(TrajectoryConfig.Point(bm.lat, bm.lng, Double.NaN)) }
                        Toast.makeText(context, "已将「${bm.name}」添加至模拟路线", Toast.LENGTH_SHORT).show()
                    },
                    onBookmarkCurrentCenter = {
                        val center = mapView.mapCenter
                        targetBookmarkPoint = GeoPoint(center.latitude, center.longitude)
                        showAddBookmarkDialog = true
                    },
                    onBookmarkMyLocation = {
                        val loc = realLocation
                        if (loc != null) {
                            targetBookmarkPoint = GeoPoint(loc.latitude, loc.longitude)
                            showAddBookmarkDialog = true
                        } else {
                            Toast.makeText(context, "尚未获取到真实定位，请稍候", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onEdit = { bm ->
                        bookmarkToEdit = bm
                    },
                    onDelete = { bm ->
                        bookmarks = BookmarkRepository.remove(context, bm.id)
                        Toast.makeText(context, "已删除「${bm.name}」", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            if (showAddBookmarkDialog && targetBookmarkPoint != null) {
                val pt = targetBookmarkPoint!!
                AddBookmarkDialog(
                    geoPoint = pt,
                    defaultName = "地点 ${bookmarks.size + 1}",
                    onDismiss = {
                        showAddBookmarkDialog = false
                        targetBookmarkPoint = null
                    },
                    onSave = { name, addToRoute ->
                        val newBm = LocationBookmark(
                            name = name,
                            lat = pt.latitude,
                            lng = pt.longitude
                        )
                        bookmarks = BookmarkRepository.add(context, newBm)
                        if (addToRoute) {
                            update { it.points().add(TrajectoryConfig.Point(pt.latitude, pt.longitude, Double.NaN)) }
                            Toast.makeText(context, "已收藏并加入模拟路线", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "已收藏「${newBm.name}」", Toast.LENGTH_SHORT).show()
                        }
                        showAddBookmarkDialog = false
                        targetBookmarkPoint = null
                    }
                )
            }

            if (bookmarkToEdit != null) {
                val bm = bookmarkToEdit!!
                EditBookmarkDialog(
                    bookmark = bm,
                    onDismiss = { bookmarkToEdit = null },
                    onConfirm = { newName ->
                        bookmarks = BookmarkRepository.update(context, bm.copy(name = newName))
                        bookmarkToEdit = null
                        Toast.makeText(context, "地点名称已更新", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // --------------------------------------------------------------
            // 二级菜单：选择真实位置与模拟位置
            // --------------------------------------------------------------
            if (showLocationMenuDialog) {
                LocationTargetMenuDialog(
                    realLocation = realLocation,
                    simPos = simPos,
                    config = config,
                    isFollowing = follow,
                    hasLocationPermission = tracker.hasLocationPermission(),
                    isProviderEnabled = tracker.isLocationProviderEnabled(),
                    onDismiss = { showLocationMenuDialog = false },
                    onLocateReal = {
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
                                Toast.makeText(context, "已定位到当前真实物理位置", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "正在获取真实定位，请稍候...", Toast.LENGTH_SHORT).show()
                                tracker.startLocationUpdates()
                            }
                        }
                    },
                    onUseRealAsSim = {
                        val loc = realLocation
                        if (loc != null) {
                            update { b ->
                                b.points().clear()
                                b.points().add(TrajectoryConfig.Point(loc.latitude, loc.longitude, Double.NaN))
                                b.enabled(true)
                                b.startAt(System.currentTimeMillis())
                            }
                            mapView.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
                            Toast.makeText(context, "已将真实物理位置设为模拟位置", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "尚未获取到真实定位，无法设为模拟点", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onMoveToSim = {
                        val p = simPos
                        val first = config.points.firstOrNull()
                        val targetLat = p?.lat ?: first?.lat
                        val targetLng = p?.lng ?: first?.lng
                        if (targetLat != null && targetLng != null) {
                            mapView.controller.animateTo(GeoPoint(targetLat, targetLng))
                            if (mapView.zoomLevelDouble < 15.0) {
                                mapView.controller.setZoom(16.0)
                            }
                            Toast.makeText(context, "已移动到模拟位置", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "尚未设置模拟位置，请搜索地点或在地图上标记", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onToggleFollow = {
                        follow = !follow
                        if (follow) {
                            val p = simPos
                            val first = config.points.firstOrNull()
                            val targetLat = p?.lat ?: first?.lat
                            val targetLng = p?.lng ?: first?.lng
                            if (targetLat != null && targetLng != null) {
                                mapView.controller.animateTo(GeoPoint(targetLat, targetLng))
                            }
                            Toast.makeText(context, "已开启模拟移动跟随", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "已关闭模拟移动跟随", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onOpenSearch = {
                        showSearchLocationDialog = true
                    }
                )
            }

            // --------------------------------------------------------------
            // 地点搜索对话框 (搜索寻找模拟位置)
            // --------------------------------------------------------------
            if (showSearchLocationDialog) {
                SearchLocationDialog(
                    onDismiss = { showSearchLocationDialog = false },
                    onSetAsSimulation = { lat, lng, name ->
                        update { b ->
                            b.points().clear()
                            b.points().add(TrajectoryConfig.Point(lat, lng, Double.NaN))
                            b.enabled(true)
                            b.startAt(System.currentTimeMillis())
                        }
                        mapView.controller.animateTo(GeoPoint(lat, lng))
                        if (mapView.zoomLevelDouble < 16.0) {
                            mapView.controller.setZoom(16.5)
                        }
                        showSearchLocationDialog = false
                        Toast.makeText(context, "已将模拟位置设为「$name」", Toast.LENGTH_SHORT).show()
                    },
                    onAddToRoute = { lat, lng, name ->
                        update { b ->
                            b.points().add(TrajectoryConfig.Point(lat, lng, Double.NaN))
                        }
                        mapView.controller.animateTo(GeoPoint(lat, lng))
                        showSearchLocationDialog = false
                        Toast.makeText(context, "已将「$name」加入路线 (共 ${config.points.size + 1} 点)", Toast.LENGTH_SHORT).show()
                    },
                    onViewOnMap = { lat, lng ->
                        mapView.controller.animateTo(GeoPoint(lat, lng))
                        if (mapView.zoomLevelDouble < 16.0) {
                            mapView.controller.setZoom(16.5)
                        }
                        showSearchLocationDialog = false
                    },
                    onBookmark = { lat, lng, name ->
                        val newBm = LocationBookmark(
                            name = name,
                            lat = lat,
                            lng = lng
                        )
                        bookmarks = BookmarkRepository.add(context, newBm)
                        Toast.makeText(context, "已收藏「$name」", Toast.LENGTH_SHORT).show()
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

/**
 * 动态纯代码生成金黄色星标收藏地点 Drawable
 */
private fun createBookmarkDrawable(context: Context): Drawable {
    val density = context.resources.displayMetrics.density
    val size = (30 * density).toInt().coerceAtLeast(30)
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val center = size / 2f
    val radius = size / 2f * 0.82f

    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.color = Color.parseColor("#FFB300")
    paint.style = android.graphics.Paint.Style.FILL
    canvas.drawCircle(center, center, radius, paint)

    paint.color = Color.WHITE
    paint.style = android.graphics.Paint.Style.STROKE
    paint.strokeWidth = 2.5f * density
    canvas.drawCircle(center, center, radius, paint)

    paint.style = android.graphics.Paint.Style.FILL
    paint.color = Color.parseColor("#E65100")
    canvas.drawCircle(center, center, radius * 0.42f, paint)

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
    onRefresh: () -> Unit,
    onSwitchToAmap: () -> Unit
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
                Text("模拟与地图运行诊断")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
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
                    }

                    // 地图显示与网络连通性深度诊断
                    val mapDiag = report.mapDiagnostic
                    if (mapDiag != null) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (mapDiag.isDisplayAbnormal)
                                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                                    else
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (mapDiag.isDisplayAbnormal) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                                            contentDescription = null,
                                            tint = if (mapDiag.isDisplayAbnormal) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "🗺️ 地图显示与网络连通分析",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    DiagnosticItem(
                                        label = "当前地图图层",
                                        value = mapDiag.currentSource.title,
                                        isOk = !mapDiag.isDisplayAbnormal
                                    )
                                    DiagnosticItem(
                                        label = "网络连接状态",
                                        value = mapDiag.networkStatus.desc,
                                        isOk = mapDiag.networkStatus.isConnected
                                    )
                                    DiagnosticItem(
                                        label = "瓦片服务器连接",
                                        value = if (mapDiag.currentProbe.reachable) "正常 (${mapDiag.currentProbe.latencyMs}ms)" else "失败/超时",
                                        isOk = mapDiag.currentProbe.reachable
                                    )

                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "原因分析：${mapDiag.issueReason}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        lineHeight = 15.sp
                                    )

                                    if (mapDiag.suggestions.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = "排查指引：",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        mapDiag.suggestions.forEach { sug ->
                                            Text(
                                                text = sug,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 11.sp,
                                                lineHeight = 15.sp,
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                        }
                                    }

                                    if (mapDiag.canSwitchToDomestic) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Button(
                                            onClick = onSwitchToAmap,
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.primary
                                            ),
                                            contentPadding = PaddingValues(vertical = 4.dp)
                                        ) {
                                            Text("⚡ 一键切换免代理的高德地图", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Text("定位与模拟指标：", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }

                    item {
                        DiagnosticItem(
                            label = "模拟运行开关",
                            value = if (report.isSimEnabled) "已开启" else "未开启",
                            isOk = report.isSimEnabled
                        )
                    }
                    item {
                        DiagnosticItem(
                            label = "轨迹就绪状态",
                            value = if (report.hasRoute) "已就绪" else "点数不足",
                            isOk = report.hasRoute
                        )
                    }
                    item {
                        DiagnosticItem(
                            label = "配置同步通道",
                            value = if (report.isChannelBound) "LSPosed 远程通道" else "本地持久化就绪",
                            isOk = true
                        )
                    }

                    if (report.expectedPos != null) {
                        item {
                            DiagnosticItem(
                                label = "当前广播坐标",
                                value = "${String.format(Locale.US, "%.5f, %.5f", report.expectedPos.lat, report.expectedPos.lng)}",
                                isOk = true
                            )
                        }
                    }

                    item {
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
                                    text = "1. 模块已切换为全局系统 Hook 模式，在 LSPosed 作用域中勾选「系统框架 (android)」即可全局生效，无需勾选各个单独 App！\n2. 在 App 内关闭开关即可立即关闭 Hook，开启即可重新生效，无需重启！\n3. 本 App 自身未被 Hook，因此在 App 内始终可获取真实定位并录制真实轨迹。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (report.actualLocation != null) {
                        item {
                            Text(
                                text = "手机当前真实 GPS: ${String.format(Locale.US, "%.5f, %.5f", report.actualLocation.latitude, report.actualLocation.longitude)} (本模块未勾选自身，故返回真实 GPS；目标应用已被 Hook 替换为模拟坐标)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onRefresh) {
                Text(if (!report.hasPermission) "读取真实 GPS 对比" else "重新探测与刷新")
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
fun MapSourceDialog(
    currentSource: MapSourceItem,
    onDismiss: () -> Unit,
    onSelectSource: (MapSourceItem) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择地图图层") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MapSourceItem.entries.forEach { item ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (currentSource == item)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                            else
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectSource(item)
                                onDismiss()
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        color = if (item.isDomestic)
                                            androidx.compose.ui.graphics.Color(0xFF2E7D32).copy(alpha = 0.2f)
                                        else
                                            androidx.compose.ui.graphics.Color(0xFFE65100).copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = if (item.isDomestic) "国内直连" else "需代理",
                                            color = if (item.isDomestic)
                                                androidx.compose.ui.graphics.Color(0xFF2E7D32)
                                            else
                                                androidx.compose.ui.graphics.Color(0xFFE65100),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = item.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                            }
                            if (currentSource == item) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = "已选",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
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

@Composable
fun BookmarksDialog(
    bookmarks: List<LocationBookmark>,
    onDismiss: () -> Unit,
    onNavigateTo: (LocationBookmark) -> Unit,
    onAddToRoute: (LocationBookmark) -> Unit,
    onBookmarkCurrentCenter: () -> Unit,
    onBookmarkMyLocation: () -> Unit,
    onEdit: (LocationBookmark) -> Unit,
    onDelete: (LocationBookmark) -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color(0xFFFFB300),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("地点收藏 (${bookmarks.size})")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 450.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onBookmarkCurrentCenter,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text("收藏地图中心", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = onBookmarkMyLocation,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text("收藏当前定位", fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                if (bookmarks.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无收藏地点。\n点击上方按钮，或在地图上长按任意位置即可快速收藏！",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(bookmarks, key = { it.id }) { bm ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = bm.name,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.titleSmall,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Row {
                                            IconButton(
                                                onClick = { onEdit(bm) },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    Icons.Filled.Edit,
                                                    contentDescription = "编辑",
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                            IconButton(
                                                onClick = { onDelete(bm) },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    Icons.Filled.Delete,
                                                    contentDescription = "删除",
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = "${String.format(Locale.US, "%.5f, %.5f", bm.lat, bm.lng)} · ${dateFmt.format(Date(bm.createTime))}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TextButton(
                                            onClick = { onAddToRoute(bm) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text("加入路线", fontSize = 12.sp)
                                        }
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Button(
                                            onClick = { onNavigateTo(bm) },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                                        ) {
                                            Text("前往", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
fun AddBookmarkDialog(
    geoPoint: GeoPoint,
    defaultName: String,
    onDismiss: () -> Unit,
    onSave: (String, Boolean) -> Unit
) {
    var name by remember { mutableStateOf(defaultName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加地点收藏") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "经纬度坐标：${String.format(Locale.US, "%.5f, %.5f", geoPoint.latitude, geoPoint.longitude)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("地点名称 / 备注") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name.trim().ifEmpty { defaultName }, false) }) {
                Text("保存")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { onSave(name.trim().ifEmpty { defaultName }, true) }) {
                    Text("保存并设为路线点")
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
}

@Composable
fun EditBookmarkDialog(
    bookmark: LocationBookmark,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(bookmark.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改地点名称") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("地点名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(name.trim().ifEmpty { bookmark.name }) }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * 二级菜单：选择真实物理位置与模拟位置
 */
@Composable
fun LocationTargetMenuDialog(
    realLocation: android.location.Location?,
    simPos: TrajectoryEngine.Position?,
    config: TrajectoryConfig,
    isFollowing: Boolean,
    hasLocationPermission: Boolean,
    isProviderEnabled: Boolean,
    onDismiss: () -> Unit,
    onLocateReal: () -> Unit,
    onUseRealAsSim: () -> Unit,
    onMoveToSim: () -> Unit,
    onToggleFollow: () -> Unit,
    onOpenSearch: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("定位与目标位置选择", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ==============================================================
                // 1. 真实物理位置 (GPS 硬件定位)
                // ==============================================================
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🎯", fontSize = 18.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "我的真实位置",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (realLocation != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.outlineVariant
                            ) {
                                Text(
                                    text = if (realLocation != null) "GPS 已锁定" else "等待定位",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    color = if (realLocation != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        val realStatusText = when {
                            !hasLocationPermission -> "尚未获取手机定位权限，点击下方按钮申请"
                            !isProviderEnabled -> "手机系统定位服务 (GPS) 未开启"
                            realLocation != null -> String.format(
                                Locale.US,
                                "真实坐标: %.5f, %.5f · 精度约 %.0f 米",
                                realLocation.latitude, realLocation.longitude, realLocation.accuracy
                            )
                            else -> "正在搜索 GPS 卫星信号，请稍候..."
                        }
                        Text(
                            text = realStatusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    onLocateReal()
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("定位真实位置", fontSize = 13.sp)
                            }

                            if (realLocation != null) {
                                OutlinedButton(
                                    onClick = {
                                        onUseRealAsSim()
                                        onDismiss()
                                    },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("设为模拟点", fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }

                // ==============================================================
                // 2. 模拟虚拟位置 (LSPosed 广播)
                // ==============================================================
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("📍", fontSize = 18.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "模拟虚拟位置",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (config.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = if (config.enabled) "模拟运行中" else "未启动",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    color = if (config.enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        val simStatusText = when {
                            config.enabled && simPos != null -> String.format(
                                Locale.US,
                                "广播坐标: %.5f, %.5f · 速度约 %.1f km/h",
                                simPos.lat, simPos.lng, config.speedMps * 3.6
                            )
                            config.points.isNotEmpty() -> String.format(
                                Locale.US,
                                "已设 %d 个点 · 目标: %.5f, %.5f",
                                config.points.size, config.points[0].lat, config.points[0].lng
                            )
                            else -> "尚未设置任何模拟坐标点"
                        }
                        Text(
                            text = simStatusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    onMoveToSim()
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text("移动到模拟点", fontSize = 13.sp)
                            }

                            OutlinedButton(
                                onClick = {
                                    onToggleFollow()
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(if (isFollowing) "停止跟随" else "开启跟随", fontSize = 13.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // 搜索寻找地点入口按钮
                        FilledTonalButton(
                            onClick = {
                                onDismiss()
                                onOpenSearch()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("🔍 搜索地点寻找模拟位置", fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

/**
 * 地点搜索对话框：搜索地名、地址或输入经纬度，一键设为模拟点或路线
 */
@Composable
fun SearchLocationDialog(
    onDismiss: () -> Unit,
    onSetAsSimulation: (lat: Double, lng: Double, name: String) -> Unit,
    onAddToRoute: (lat: Double, lng: Double, name: String) -> Unit,
    onViewOnMap: (lat: Double, lng: Double) -> Unit,
    onBookmark: (lat: Double, lng: Double, name: String) -> Unit
) {
    var queryText by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<SearchResultPlace>>(emptyList()) }
    var hasSearched by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val doSearch: () -> Unit = {
        val q = queryText.trim()
        if (q.isNotEmpty()) {
            isSearching = true
            errorMessage = null
            hasSearched = true
            scope.launch {
                runCatching {
                    LocationSearchService.search(q)
                }.onSuccess { list ->
                    searchResults = list
                    isSearching = false
                }.onFailure { err ->
                    errorMessage = err.localizedMessage ?: "搜索失败，请检查网络"
                    isSearching = false
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("地点搜索 (模拟位置)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = queryText,
                        onValueChange = { queryText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("地名、路名或经纬度坐标...", fontSize = 13.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                        trailingIcon = {
                            if (queryText.isNotEmpty()) {
                                IconButton(onClick = { queryText = ""; searchResults = emptyList(); hasSearched = false }) {
                                    Icon(Icons.Filled.Clear, contentDescription = "清空", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    )
                    Button(
                        onClick = doSearch,
                        enabled = !isSearching && queryText.isNotBlank(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text("搜索", fontSize = 13.sp)
                    }
                }

                if (isSearching) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "正在全网检索地点，请稍候...",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (errorMessage != null) {
                    Text(
                        "搜索出错: $errorMessage",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }

                if (!isSearching && hasSearched && searchResults.isEmpty() && errorMessage == null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("未找到相关地点", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "提示：可以尝试搜索更大的行政区、标志性建筑名，或直接输入经纬度坐标（如「39.9087, 116.3975」）。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }

                if (!hasSearched && searchResults.isEmpty()) {
                    Text(
                        "💡 支持搜索全球地名、景点、车站、公司，或输入格式如「纬度, 经度」的坐标数值定位。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                }

                if (searchResults.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(searchResults) { item ->
                            Card(
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = item.title,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Text(
                                                text = item.source,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }

                                    if (item.description.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = item.description,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            lineHeight = 15.sp
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = String.format(Locale.US, "坐标: %.5f, %.5f", item.lat, item.lng),
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.outline
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Button(
                                            onClick = { onSetAsSimulation(item.lat, item.lng, item.title) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Text("设为模拟点", fontSize = 11.sp)
                                        }

                                        OutlinedButton(
                                            onClick = { onAddToRoute(item.lat, item.lng, item.title) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Text("+ 路线", fontSize = 11.sp)
                                        }

                                        OutlinedButton(
                                            onClick = { onViewOnMap(item.lat, item.lng) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Text("查看", fontSize = 11.sp)
                                        }

                                        IconButton(
                                            onClick = { onBookmark(item.lat, item.lng, item.title) },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                Icons.Filled.Star,
                                                contentDescription = "收藏",
                                                tint = androidx.compose.ui.graphics.Color(0xFFFFB300),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}
