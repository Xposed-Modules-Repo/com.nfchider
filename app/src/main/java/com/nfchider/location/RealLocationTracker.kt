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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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

    /**
     * BUG-13 fix: 录制轨迹时最近一次录入的原始定位。
     * 轨迹降噪需要比对「新旧 fix 的相对精度」：若新 fix 精度更差且距上一点很近，
     * 说明它是上一点（更精确的 GPS fix）附近的网络定位抖动，录入会污染轨迹。
     */
    private var lastRecordedLocation: Location? = null

    /**
     * BUG-13 fix: 初始种子定位是否陈旧。陈旧种子（如早上最后一次 GPS 缓存）会让
     * isBetterLocation 因「新 fix 并非显著更新」而错误拒绝实时 fix，此标记置位时
     * 首个更新的实时 fix 直接替换种子。
     */
    private var seedIsStale = false

    /**
     * BUG-06 fix: source of truth for recorded waypoints. Fixes used to rebuild
     * the whole list on every callback ([List.plus]), i.e. O(n^2) allocations
     * over a long recording. Points are now appended here in O(1) and the
     * publicly observed snapshot is flushed on a throttle, which also matches
     * the map redraw cadence.
     */
    private val recordedPointsBuffer = ArrayList<TrajectoryConfig.Point>()
    private var flushPending = false

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
        //    BUG-13 fix: 原来按 .time 取「最新」缓存，可能选中刚过期的高精度 GPS，
        //    也可能选中粗精度的网络缓存；改用 isBetterLocation 择优。
        seedIsStale = false
        var bestLastLocation: Location? = null
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            val loc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (loc != null && isBetterLocation(loc, bestLastLocation)) {
                bestLastLocation = loc
            }
        }
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            val loc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (loc != null && isBetterLocation(loc, bestLastLocation)) {
                bestLastLocation = loc
            }
        }
        bestLastLocation?.let { cached ->
            val ageMs = System.currentTimeMillis() - cached.time
            when {
                // 超过 10 分钟的缓存定位没有展示价值（可能是昨天/今早的残留），不作为初始估计
                ageMs > 10 * 60 * 1000L -> return@let
                // 陈旧缓存（>2 分钟）：仍可作为初始估计，但经典启发式会因「新 fix
                // 相对陈旧种子并不显著更新」而错误拒绝实时 fix，需标记允许直接替换
                ageMs > TWO_MINUTES_MS -> {
                    seedIsStale = true
                    handleNewLocation(cached)
                }
                else -> handleNewLocation(cached)
            }
        }

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
     * 释放资源：取消协程作用域并停止定位监听与录制计时器。
     * 应在宿主(Activity/Composable)销毁时调用，避免协程与监听器泄漏。
     */
    fun release() {
        stopLocationUpdates()
        stopTimer()
        scope.cancel()
    }

    /**
     * 处理收到的最新定位信息（更新实时位置与轨迹录制过滤采样）
     *
     * BUG-13 fix: GPS 与 NETWORK 两个 provider 注册在同一个 listener 上，原来
     * 每次回调都无条件覆盖 _currentLocation：一次 500m 精度的基站定位会把刚
     * 锁定的 5m GPS fix 覆盖掉，蓝点（以及「定位到我」「设为模拟点」）随之跳
     * 到错误位置。现在按经典 isBetterLocation 启发式择优，_currentLocation
     * 只持有「更新且更准」的 fix。
     */
    private fun handleNewLocation(location: Location) {
        // 更新当前真实定位：仅当新 fix 优于当前最佳（或用于替换陈旧种子）时才覆盖
        val currentBest = _currentLocation.value
        val replacesStaleSeed = seedIsStale && currentBest != null && location.time > currentBest.time
        if (replacesStaleSeed || isBetterLocation(location, currentBest)) {
            _currentLocation.value = location
            seedIsStale = false
        }

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

        val lastPoint = recordedPointsBuffer.lastOrNull()
        val speed = if (location.hasSpeed()) location.speed.toDouble() else 0.0
        _currentSpeedMps.value = speed

        val ele = if (location.hasAltitude()) location.altitude else Double.NaN
        val newPoint = TrajectoryConfig.Point(location.latitude, location.longitude, ele)

        if (lastPoint == null) {
            // 首个点直接录入
            recordedPointsBuffer.add(newPoint)
            lastRecordedLocation = location
            scheduleRecordedFlush()
        } else {
            val dist = distanceBetween(lastPoint.lat, lastPoint.lng, newPoint.lat, newPoint.lng)
            // 2. 距离过滤：如果距离上一个航点小于 3 米，且当前几乎处于静止状态，则不重复记录以防原地漂移画圈
            if (dist < 3.0 && speed < 0.6) {
                return
            }

            // 3. BUG-13 fix: 抖动过滤。若新 fix 精度明显差于最近录入的 fix，且距离
            //    很近（<10m），判定为网络定位对已锁定 GPS 位置的抖动，跳过；
            //    反之更精确的 fix（或明显移动）予以录入，让轨迹向高精度收敛。
            val lastRecorded = lastRecordedLocation
            if (lastRecorded != null && dist < 10.0 && location.hasAccuracy() && lastRecorded.hasAccuracy()) {
                if (location.accuracy > lastRecorded.accuracy + 5f) {
                    return
                }
            }

            recordedPointsBuffer.add(newPoint)
            lastRecordedLocation = location
            _recordedDistanceM.value += dist
            scheduleRecordedFlush()
        }
    }

    /** Publishes the recorded-points snapshot at most ~2x per second. */
    private fun scheduleRecordedFlush() {
        if (flushPending) return
        flushPending = true
        scope.launch {
            delay(400L)
            flushPending = false
            _recordedPoints.value = ArrayList(recordedPointsBuffer)
        }
    }

    // -------------------------------------------------------------------------
    // 轨迹录制控制
    // -------------------------------------------------------------------------

    /**
     * 开始录制
     */
    fun startRecording() {
        recordedPointsBuffer.clear()
        lastRecordedLocation = null
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
                recordedPointsBuffer.add(TrajectoryConfig.Point(loc.latitude, loc.longitude, ele))
                lastRecordedLocation = loc
                scheduleRecordedFlush()
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
        val result = ArrayList(recordedPointsBuffer)
        lastRecordedLocation = null
        _recordingState.value = RecordingState.IDLE
        stopTimer()
        return result
    }

    /**
     * 放弃并清空录制
     */
    fun cancelRecording() {
        _recordingState.value = RecordingState.IDLE
        recordedPointsBuffer.clear()
        lastRecordedLocation = null
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
        /** 判定「明显更新/明显更旧」的时间阈值（2 分钟），与 Android 官方最佳定位启发式一致 */
        private const val TWO_MINUTES_MS = 2 * 60 * 1000L

        /** 判定「精度显著变差」的阈值（米） */
        private const val SIGNIFICANTLY_LESS_ACCURATE_M = 200f

        /**
         * 经典最佳定位启发式（源自 Android 开发者文档）：
         * 判定 [newLocation] 是否应取代 [currentBestLocation] 成为当前最佳定位。
         *
         * - 无当前定位时，任何新定位都更优；
         * - 新定位比当前最佳「明显更新」（>2 分钟）：接受（用户很可能已移动）；
         * - 新定位「明显更旧」（<-2 分钟）：拒绝；
         * - 精度更高：接受；
         * - 更新且精度未变差：接受；
         * - 更新、精度变差但未显著变差且同 provider：接受；
         * - 其余拒绝。
         *
         * 缺精度信息（hasAccuracy=false）按最差精度处理，避免无精度 fix 覆盖精确 fix。
         */
        fun isBetterLocation(newLocation: Location, currentBestLocation: Location?): Boolean {
            if (currentBestLocation == null) return true

            val timeDelta = newLocation.time - currentBestLocation.time
            val isSignificantlyNewer = timeDelta > TWO_MINUTES_MS
            val isSignificantlyOlder = timeDelta < -TWO_MINUTES_MS
            val isNewer = timeDelta > 0

            if (isSignificantlyNewer) {
                return true
            } else if (isSignificantlyOlder) {
                return false
            }

            val newAccuracy = if (newLocation.hasAccuracy()) newLocation.accuracy else Float.MAX_VALUE
            val bestAccuracy = if (currentBestLocation.hasAccuracy()) currentBestLocation.accuracy else Float.MAX_VALUE
            val accuracyDelta = newAccuracy - bestAccuracy
            val isMoreAccurate = accuracyDelta < 0
            val isLessAccurate = accuracyDelta > 0
            val isSignificantlyLessAccurate = accuracyDelta > SIGNIFICANTLY_LESS_ACCURATE_M
            val isFromSameProvider = newLocation.provider == currentBestLocation.provider

            return when {
                isMoreAccurate -> true
                isNewer && !isLessAccurate -> true
                isNewer && !isSignificantlyLessAccurate && isFromSameProvider -> true
                else -> false
            }
        }

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
