package com.nfchider.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 真实定位与轨迹录制服务管理器
 * 1. 监听手机真实 GPS/基站网络定位，提供即时真实位置；
 * 2. 具备智能降噪与滤波的真实路线轨迹录制器（采样、测距、防抖、导出 GPX 等）。
 */
class RealLocationTracker(private val context: Context) {

    enum class RecordingState {
        IDLE, RECORDING, PAUSED
    }

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // -------------------------------------------------------------------------
    // 真实定位状态
    // -------------------------------------------------------------------------
    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    // -------------------------------------------------------------------------
    // 轨迹录制状态
    // -------------------------------------------------------------------------
    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _recordedPoints = MutableStateFlow<List<TrajectoryConfig.Point>>(emptyList())
    val recordedPoints: StateFlow<List<TrajectoryConfig.Point>> = _recordedPoints.asStateFlow()

    private val _recordedDistanceM = MutableStateFlow(0.0)
    val recordedDistanceM: StateFlow<Double> = _recordedDistanceM.asStateFlow()

    private val _currentSpeedMps = MutableStateFlow(0.0)
    val currentSpeedMps: StateFlow<Double> = _currentSpeedMps.asStateFlow()

    private val _recordingDurationSec = MutableStateFlow(0L)
    val recordingDurationSec: StateFlow<Long> = _recordingDurationSec.asStateFlow()

    private var timerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleNewLocation(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    /**
     * 检查是否具备定位权限
     */
    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 判断系统是否开启了定位服务（GPS 或网络定位）
     */
    fun isLocationProviderEnabled(): Boolean {
        val gps = runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        val net = runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        return gps || net
    }

    /**
     * 开启真实定位监听
     */
    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        if (_isListening.value) return

        // 1. 尝试快速读取最近一次缓存定位
        var bestLastLocation: Location? = null
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            val loc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (loc != null) bestLastLocation = loc
        }
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            val loc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (loc != null) {
                if (bestLastLocation == null || loc.time > bestLastLocation.time) {
                    bestLastLocation = loc
                }
            }
        }
        bestLastLocation?.let { handleNewLocation(it) }

        // 2. 注册主动定位监听器（GPS 优先，辅以基站/网络定位）
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    1.0f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }
        } catch (_: SecurityException) {}

        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
                    1.0f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }
        } catch (_: SecurityException) {}

        _isListening.value = true
    }

    /**
     * 停止定位监听
     */
    fun stopLocationUpdates() {
        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: Exception) {}
        _isListening.value = false
    }

    /**
     * 处理收到的最新定位信息（更新实时位置与轨迹录制过滤采样）
     */
    private fun handleNewLocation(location: Location) {
        // 更新当前真实定位
        _currentLocation.value = location

        if (_recordingState.value != RecordingState.RECORDING) {
            return
        }

        // ---------------------------------------------------------------------
        // 轨迹录制采样与降噪过滤
        // ---------------------------------------------------------------------
        // 1. 剔除精度过差的噪点（如精度大于 35 米）
        if (location.hasAccuracy() && location.accuracy > 35f) {
            return
        }

        val points = _recordedPoints.value
        val lastPoint = points.lastOrNull()
        val speed = if (location.hasSpeed()) location.speed.toDouble() else 0.0
        _currentSpeedMps.value = speed

        val ele = if (location.hasAltitude()) location.altitude else Double.NaN
        val newPoint = TrajectoryConfig.Point(location.latitude, location.longitude, ele)

        if (lastPoint == null) {
            // 首个点直接录入
            _recordedPoints.value = listOf(newPoint)
        } else {
            val dist = distanceBetween(lastPoint.lat, lastPoint.lng, newPoint.lat, newPoint.lng)
            // 2. 距离过滤：如果距离上一个航点小于 3 米，且当前几乎处于静止状态，则不重复记录以防原地漂移画圈
            if (dist < 3.0 && speed < 0.6) {
                return
            }

            _recordedPoints.value = points + newPoint
            _recordedDistanceM.value += dist
        }
    }

    // -------------------------------------------------------------------------
    // 轨迹录制控制
    // -------------------------------------------------------------------------

    /**
     * 开始录制
     */
    fun startRecording() {
        _recordedPoints.value = emptyList()
        _recordedDistanceM.value = 0.0
        _currentSpeedMps.value = 0.0
        _recordingDurationSec.value = 0L
        _recordingState.value = RecordingState.RECORDING
        startTimer()

        // 如果之前有当前位置，直接作为初始点
        _currentLocation.value?.let { loc ->
            if (!loc.hasAccuracy() || loc.accuracy <= 35f) {
                val ele = if (loc.hasAltitude()) loc.altitude else Double.NaN
                _recordedPoints.value = listOf(TrajectoryConfig.Point(loc.latitude, loc.longitude, ele))
            }
        }
    }

    /**
     * 暂停录制
     */
    fun pauseRecording() {
        if (_recordingState.value == RecordingState.RECORDING) {
            _recordingState.value = RecordingState.PAUSED
            stopTimer()
        }
    }

    /**
     * 恢复录制
     */
    fun resumeRecording() {
        if (_recordingState.value == RecordingState.PAUSED) {
            _recordingState.value = RecordingState.RECORDING
            startTimer()
        }
    }

    /**
     * 完成录制，返回所有采集的点，并重置录制状态
     */
    fun finishRecording(): List<TrajectoryConfig.Point> {
        val result = _recordedPoints.value
        _recordingState.value = RecordingState.IDLE
        stopTimer()
        return result
    }

    /**
     * 放弃并清空录制
     */
    fun cancelRecording() {
        _recordingState.value = RecordingState.IDLE
        _recordedPoints.value = emptyList()
        _recordedDistanceM.value = 0.0
        _currentSpeedMps.value = 0.0
        _recordingDurationSec.value = 0L
        stopTimer()
    }

    private fun startTimer() {
        stopTimer()
        timerJob = scope.launch {
            while (isActive) {
                delay(1000L)
                if (_recordingState.value == RecordingState.RECORDING) {
                    _recordingDurationSec.value += 1
                }
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    companion object {
        /**
         * 计算两个经纬度之间的球面距离（米）
         */
        fun distanceBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val results = FloatArray(1)
            Location.distanceBetween(lat1, lng1, lat2, lng2, results)
            return results[0].toDouble()
        }

        /**
         * 将航点导出为标准 GPX 1.1 XML 字符串
         */
        fun generateGpx(points: List<TrajectoryConfig.Point>, trackName: String = "NfcHider Recorded Track"): String {
            val sb = java.lang.StringBuilder()
            val timeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            val nowTimeStr = timeFormat.format(Date())

            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            sb.append("<gpx version=\"1.1\" creator=\"NfcHider\"\n")
            sb.append("  xmlns=\"http://www.topografix.com/GPX/1/1\"\n")
            sb.append("  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
            sb.append("  xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
            sb.append("  <metadata>\n")
            sb.append("    <name>").append(escapeXml(trackName)).append("</name>\n")
            sb.append("    <time>").append(nowTimeStr).append("</time>\n")
            sb.append("  </metadata>\n")
            sb.append("  <trk>\n")
            sb.append("    <name>").append(escapeXml(trackName)).append("</name>\n")
            sb.append("    <trkseg>\n")

            for (p in points) {
                sb.append("      <trkpt lat=\"").append(String.format(Locale.US, "%.6f", p.lat))
                    .append("\" lon=\"").append(String.format(Locale.US, "%.6f", p.lng)).append("\">\n")
                if (!p.ele.isNaN()) {
                    sb.append("        <ele>").append(String.format(Locale.US, "%.2f", p.ele)).append("</ele>\n")
                }
                sb.append("      </trkpt>\n")
            }

            sb.append("    </trkseg>\n")
            sb.append("  </trk>\n")
            sb.append("</gpx>")

            return sb.toString()
        }

        private fun escapeXml(input: String): String {
            return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
        }
    }
}
