package com.nfchider.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
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
import kotlin.math.abs
import kotlin.math.sqrt

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
    // 定位增强状态（BUG-15）
    // -------------------------------------------------------------------------
    /** GNSS 可见卫星总数（诊断与状态展示用） */
    private val _satelliteCount = MutableStateFlow(0)
    val satelliteCount: StateFlow<Int> = _satelliteCount.asStateFlow()

    /** 参与解算的卫星数（usedInFix） */
    private val _satellitesUsedInFix = MutableStateFlow(0)
    val satellitesUsedInFix: StateFlow<Int> = _satellitesUsedInFix.asStateFlow()

    /** 设备是否静止（加速度计判定）：静止时冻结蓝点，抑制室内 GPS 漂移跳变 */
    private val _isStationary = MutableStateFlow(false)
    val isStationary: StateFlow<Boolean> = _isStationary.asStateFlow()

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private var stationarySinceMs = 0L

    /** Kalman-lite 融合状态：平滑经纬度与方差（米²） */
    private var smoothLat = 0.0
    private var smoothLng = 0.0
    private var smoothVar = 0.0
    private var smoothValid = false

    /** GPS 锁：GPS fix 精度达到门限即锁定，锁定期间网络定位不得覆盖 */
    private var gpsLockAccuracy: Float? = null
    private var lastGpsFixAt = 0L

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
     * GNSS 卫星状态回调：统计可见卫星与参与解算的卫星数。
     * 卫星数少（<4 星参与解算）时定位精度必然差，展示给用户可解释
     * 「为什么定位不准」，并提示移动到开阔处。
     */
    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            val total = status.satelliteCount
            for (i in 0 until total) {
                if (status.usedInFix(i)) used++
            }
            _satelliteCount.value = total
            _satellitesUsedInFix.value = used
        }
    }

    /**
     * 加速度计监听（静止检测）：合加速度持续贴近重力加速度（|a|-9.81 < 0.6）
     * 超过 4 秒判定设备静止。静止期间冻结蓝点显示位置，杜绝室内 GPS 多径
     * 漂移造成的「蓝点跳舞」；一旦重新移动立即解除冻结。
     */
    private val accelerometerListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val magnitude = sqrt((x * x + y * y + z * z).toDouble())
            val now = System.currentTimeMillis()
            if (abs(magnitude - 9.81) < STATIONARY_ACCEL_THRESHOLD) {
                if (stationarySinceMs == 0L) stationarySinceMs = now
                if (!_isStationary.value && now - stationarySinceMs >= STATIONARY_HOLD_MS) {
                    _isStationary.value = true
                }
            } else {
                stationarySinceMs = 0L
                _isStationary.value = false
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
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
        //    GPS 用 minTime=0/minDistance=0：每个 fix 都送入择优与融合层，
        //    避免系统节流漏掉关键的高精度 fix；网络定位 2s 一次足够（它
        //    只服务于 GPS 不可用的兜底场景，且锁定 GPS 后会被隔离）。
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    0L,
                    0f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }
        } catch (_: SecurityException) {}

        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2000L,
                    0f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }
        } catch (_: SecurityException) {}

        // 3. 注册 GNSS 卫星状态与加速度计（静止检测）监听
        try {
            locationManager.registerGnssStatusCallback(gnssStatusCallback, Handler(Looper.getMainLooper()))
        } catch (_: Exception) {}
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensor ->
            try {
                sensorManager.registerListener(accelerometerListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            } catch (_: Exception) {}
        }

        // 4. 重置融合与锁定状态，避免上一轮监听的平滑位置污染新一轮定位
        smoothValid = false
        gpsLockAccuracy = null
        lastGpsFixAt = 0L

        _isListening.value = true
    }

    /**
     * 停止定位监听
     */
    fun stopLocationUpdates() {
        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: Exception) {}
        try {
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
        } catch (_: Exception) {}
        try {
            sensorManager?.unregisterListener(accelerometerListener)
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
     *
     * BUG-15 fix: 在择优之上叠加三层增强，继续压胜定位精度：
     * 1. provider 隔离——GPS 锁定（精度 ≤30m 且 30s 内有 fix）后，网络定位
     *    不得覆盖蓝点，除非精度反超 GPS 锁 20m 以上（WiFi 定位偶有惊喜）；
     * 2. Kalman-lite 融合——相邻 fix 在合成噪声带内时按方差倒数加权平均，
     *    多 fix 一致即收敛蓝点、压低显示精度；超出噪声带（真实移动）直接
     *    跳转不拖尾；
     * 3. 静止冻结——加速度计判定设备静止时冻结显示位置，只接纳显著更优的
     *    fix，杜绝室内多径漂移的「蓝点跳舞」。
     */
    private fun handleNewLocation(location: Location) {
        val now = System.currentTimeMillis()
        val isGps = location.provider == LocationManager.GPS_PROVIDER
        val fixAccuracy = if (location.hasAccuracy()) location.accuracy else DEFAULT_ACCURACY_M

        // GPS 锁状态跟踪：GPS fix 精度达到门限即视为锁定，记录锁定精度与时间
        if (isGps && location.hasAccuracy() && location.accuracy <= GPS_LOCK_ACCURACY_M) {
            gpsLockAccuracy = location.accuracy
            lastGpsFixAt = now
        }

        // ---------------------------------------------------------------------
        // 增强 1：provider 隔离。GPS 已锁定且未陈旧时，网络/基站定位不得
        // 覆盖蓝点（它通常粗 1-2 个数量级）；GPS 超过 30s 无 fix 视为失锁，
        // 网络定位重新参与择优。
        // ---------------------------------------------------------------------
        if (!isGps) {
            val lockAcc = gpsLockAccuracy
            if (lockAcc != null) {
                if (now - lastGpsFixAt >= GPS_LOCK_STALE_MS) {
                    gpsLockAccuracy = null
                } else if (fixAccuracy >= lockAcc - NETWORK_OVERRIDE_MARGIN_M) {
                    return
                }
            }
        }

        // 择优门控：仅当新 fix 优于当前最佳（或用于替换陈旧种子）时才进入后续处理
        val currentBest = _currentLocation.value
        val replacesStaleSeed = seedIsStale && currentBest != null && location.time > currentBest.time
        if (!(replacesStaleSeed || isBetterLocation(location, currentBest))) {
            return
        }
        seedIsStale = false

        // ---------------------------------------------------------------------
        // 增强 2 + 3：Kalman-lite 融合与静止冻结，输出平滑后的显示位置
        // ---------------------------------------------------------------------
        val sigma2 = (fixAccuracy * fixAccuracy).toDouble()
        when {
            // 静止冻结：只接纳显著更优的 fix 或超出静止移动阈值的真实移动
            _isStationary.value && currentBest != null -> {
                val dist = if (smoothValid) {
                    distanceBetween(smoothLat, smoothLng, location.latitude, location.longitude)
                } else {
                    0.0
                }
                val curAcc = if (currentBest.hasAccuracy()) currentBest.accuracy else DEFAULT_ACCURACY_M
                val significantlyBetter = location.hasAccuracy() && location.accuracy < curAcc * 0.7f
                if (significantlyBetter || dist > STATIONARY_MOVE_M) {
                    smoothLat = location.latitude
                    smoothLng = location.longitude
                    smoothVar = sigma2
                    smoothValid = true
                    emitSmoothed(location)
                }
                // 否则冻结：保持当前显示位置不变
            }

            // 首个 fix：直接作为平滑状态初值
            !smoothValid -> {
                smoothLat = location.latitude
                smoothLng = location.longitude
                smoothVar = sigma2
                smoothValid = true
                emitSmoothed(location)
            }

            else -> {
                val dist = distanceBetween(smoothLat, smoothLng, location.latitude, location.longitude)
                val combinedSigma = sqrt(smoothVar + sigma2)
                if (dist > maxOf(SMOOTH_JUMP_SIGMAS * combinedSigma, SMOOTH_JUMP_MIN_M)) {
                    // 超出合成噪声带：判定为真实移动，直接跳转（不平滑拖尾）
                    smoothLat = location.latitude
                    smoothLng = location.longitude
                    smoothVar = sigma2
                    smoothValid = true
                    emitSmoothed(location)
                } else {
                    // 噪声带内：按方差倒数加权融合，收敛蓝点并压低显示精度
                    val invSum = 1.0 / smoothVar + 1.0 / sigma2
                    val wSmooth = (1.0 / smoothVar) / invSum
                    val wFix = (1.0 / sigma2) / invSum
                    smoothLat = smoothLat * wSmooth + location.latitude * wFix
                    smoothLng = smoothLng * wSmooth + location.longitude * wFix
                    // 方差下限：宣称精度不低于单 fix 的一半，避免过度自信
                    smoothVar = maxOf(1.0 / invSum, sigma2 * 0.25)
                    emitSmoothed(location)
                }
            }
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

    /**
     * 以平滑后的位置发布当前真实定位。
     * 保留源 fix 的 provider/time/speed 等属性，仅替换经纬度，
     * accuracy 取融合后方差（多 fix 一致时低于任何单 fix，体现置信度提升）。
     */
    private fun emitSmoothed(source: Location) {
        val out = Location(source)
        out.latitude = smoothLat
        out.longitude = smoothLng
        if (source.hasAccuracy()) {
            out.accuracy = sqrt(smoothVar).coerceAtLeast(3.0).toFloat()
        }
        _currentLocation.value = out
        seedIsStale = false
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

        // ------------------------------------------------------------------
        // BUG-15 定位增强常量
        // ------------------------------------------------------------------
        /** GPS 锁定判定精度（米）：GPS fix 精度优于此值即视为已锁定 */
        private const val GPS_LOCK_ACCURACY_M = 30f

        /** GPS 锁失保质期（毫秒）：超过此时长没有 GPS fix 视为失锁 */
        private const val GPS_LOCK_STALE_MS = 30_000L

        /** 网络定位覆盖 GPS 锁所需的精度优势（米） */
        private const val NETWORK_OVERRIDE_MARGIN_M = 20f

        /** 无精度信息的 fix 的保守默认精度（米） */
        private const val DEFAULT_ACCURACY_M = 50f

        /** 静止判定：合加速度相对重力的偏差阈值（m/s²） */
        private const val STATIONARY_ACCEL_THRESHOLD = 0.6

        /** 静止判定保持时长（毫秒） */
        private const val STATIONARY_HOLD_MS = 4_000L

        /** 静止冻结期间判定「真实移动」的距离阈值（米） */
        private const val STATIONARY_MOVE_M = 15.0

        /** 平滑跳变判定：超出 N 倍合成标准差视为真实移动 */
        private const val SMOOTH_JUMP_SIGMAS = 3.0

        /** 平滑跳变判定的最小距离（米），避免高置信态下噪声误触发跳转 */
        private const val SMOOTH_JUMP_MIN_M = 8.0

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
