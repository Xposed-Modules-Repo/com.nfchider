package com.nfchider

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.nfchider.location.ModuleService
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

    val update: ((TrajectoryConfig.Builder) -> Unit) -> Unit = { transform ->
        val builder = config.toBuilder()
        transform(builder)
        config = builder.build()
        SimConfigRepository.save(context, config)
    }

    // ------------------------------------------------------------------
    // Map
    // ------------------------------------------------------------------

    val tapHandler = remember { mutableStateOf<(GeoPoint) -> Unit>({}) }

    val routeLine = remember {
        Polyline().apply {
            outlinePaint.color = Color.parseColor("#3F8CFF")
            outlinePaint.strokeWidth = 9f
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
        }
    }

    val posMarker = remember {
        Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "模拟当前位置"
            isEnabled = false
        }.also { mapView.overlays.add(it) }
    }

    tapHandler.value = { geo ->
        if (markMode && !config.enabled) {
            update { it.points().add(TrajectoryConfig.Point(geo.latitude, geo.longitude, Double.NaN)) }
        }
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose { mapView.onPause() }
    }

    // Rebuild waypoints + route polyline when the point list changes.
    LaunchedEffect(config.points.size) {
        val overlays = mapView.overlays
        overlays.removeAll { it is Marker && it !== posMarker }
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

    // Live preview ticker of the simulated position.
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

    // Config channel status poll (service binding may happen late).
    LaunchedEffect(Unit) {
        while (true) {
            channelBound = ModuleService.bound
            delay(2000)
        }
    }

    // ------------------------------------------------------------------
    // GPX import
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
    // Layout
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

            if (config.points.isEmpty()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f)
                ) {
                    Text(
                        text = "开启「标记」后点击地图添加轨迹点（至少 2 个，或导入 GPX）",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        fontSize = 13.sp
                    )
                }
            }

            // Zoom + follow controls
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MapFab(icon = Icons.Filled.Add) { mapView.controller.zoomIn() }
                MapFabText(label = "-") { mapView.controller.zoomOut() }
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

            // Bottom control panel
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

                    // Speed
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

                    // Speed presets
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("步行" to 5, "骑行" to 15, "驾车" to 40, "高铁" to 300).forEach { (label, kmh) ->
                            FilterChip(
                                selected = (config.speedMps * 3.6).roundToInt() == kmh,
                                onClick = { commitSpeed(kmh / 3.6) },
                                label = { Text("$label $kmh") }
                            )
                        }
                    }

                    // Playback mode
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

                    // Editing actions
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
                        Spacer(modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { gpxLauncher.launch(arrayOf("*/*")) }) {
                            Text("导入 GPX")
                        }
                    }

                    // Start / stop
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
                        text = "提示：需在 LSPosed 勾选目标应用并重启目标应用；编辑轨迹时请先停止模拟。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
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
    meters >= 1000 -> String.format("%.1f km", meters / 1000)
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
    } catch (t: Throwable) {
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
