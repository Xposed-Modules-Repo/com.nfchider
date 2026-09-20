package com.nfchider.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.nfchider.LocationHook
import kotlin.math.*

/**
 * Diagnostic utility that verifies whether the Xposed location simulation hook
 * is actively intercepting location requests on the device.
 */
object LocationSelfTest {

    enum class TestStatus {
        SUCCESS,            // Hook is active and system returned simulated position
        HOOK_INACTIVE,      // Xposed/LSPosed hook is not active in this process
        SIM_DISABLED,       // Simulation is not turned on
        NO_PERMISSION,      // Location permission missing to perform self-test
        NO_LOCATION_FIX,    // Hook is active, but no cached fix has been requested yet
        FAILED_REAL_LOC     // Read real device location; hook did not intercept
    }

    data class DiagnosticReport(
        val isHookActive: Boolean,
        val isSimEnabled: Boolean,
        val hasRoute: Boolean,
        val isChannelBound: Boolean,
        val hasPermission: Boolean,
        val actualLocation: Location?,
        val expectedPos: TrajectoryEngine.Position?,
        val distanceDiffMeters: Double?,
        val status: TestStatus,
        val summaryText: String,
        val detailText: String
    )

    fun isHookActive(): Boolean {
        return runCatching { LocationHook.isHookActive() }.getOrDefault(false)
    }

    fun runDiagnostics(context: Context, config: TrajectoryConfig): DiagnosticReport {
        val hookActive = isHookActive()
        val simEnabled = config.enabled
        val hasRoute = config.hasRoute()
        val channelBound = ModuleService.bound

        val hasFine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasPermission = hasFine || hasCoarse

        val now = System.currentTimeMillis()
        val expectedPos = if (simEnabled && hasRoute) TrajectoryEngine.positionAt(config, now) else null

        var actualLoc: Location? = null
        if (hasPermission) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm != null) {
                runCatching {
                    actualLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                        ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            lm.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
                        } else null
                }
            }
        }

        var distanceDiff: Double? = null
        if (actualLoc != null && expectedPos != null) {
            distanceDiff = calculateDistance(
                actualLoc.latitude, actualLoc.longitude,
                expectedPos.lat, expectedPos.lng
            )
        }

        val status: TestStatus
        val summary: String
        val detail: String

        when {
            !simEnabled -> {
                status = TestStatus.SIM_DISABLED
                summary = "模拟未开启"
                detail = "请先点击下方的「开始模拟」按钮启动轨迹模拟。"
            }
            !hasRoute -> {
                status = TestStatus.SIM_DISABLED
                summary = "未设置轨迹点"
                detail = "当前轨迹点不足 2 个，请先在地图上添加轨迹点或导入 GPX 文件。"
            }
            !hookActive -> {
                status = TestStatus.HOOK_INACTIVE
                summary = "Xposed Hook 未生效"
                detail = "当前应用未被 LSPosed 框架注入 Hook。请在 LSPosed 中勾选本应用与目标应用作用域，并重启应用。"
            }
            !hasPermission -> {
                status = TestStatus.NO_PERMISSION
                summary = "未授予自测定位权限"
                detail = "已开启模拟，请授予应用定位权限以供系统级读取对比自检。"
            }
            actualLoc != null && distanceDiff != null && distanceDiff < 100.0 -> {
                status = TestStatus.SUCCESS
                summary = "位置模拟已生效！"
                detail = "系统已成功返回模拟轨迹坐标（当前经纬度: ${String.format("%.5f, %.5f", actualLoc.latitude, actualLoc.longitude)}，与期望偏差仅 ${distanceDiff.roundToInt()} 米）。"
            }
            actualLoc != null && distanceDiff != null && distanceDiff >= 100.0 -> {
                status = TestStatus.FAILED_REAL_LOC
                summary = "模拟未生效（读到外部位置）"
                detail = "系统读取到的位置（${String.format("%.4f, %.4f", actualLoc.latitude, actualLoc.longitude)}）与模拟目标偏差 ${String.format("%.1f", distanceDiff / 1000)} km，请确认 LSPosed 作用域并重启目标应用。"
            }
            else -> {
                status = if (hookActive) TestStatus.SUCCESS else TestStatus.NO_LOCATION_FIX
                summary = if (hookActive) "Hook 注入成功，等待系统刷新" else "暂无定位缓存"
                detail = if (hookActive) "Xposed Hook 已成功注入，目标应用调用系统定位时将自动拦截并返回模拟坐标。"
                else "系统尚无缓存定位，请点击开始模拟并在目标应用内刷新定位。"
            }
        }

        return DiagnosticReport(
            isHookActive = hookActive,
            isSimEnabled = simEnabled,
            hasRoute = hasRoute,
            isChannelBound = channelBound,
            hasPermission = hasPermission,
            actualLocation = actualLoc,
            expectedPos = expectedPos,
            distanceDiffMeters = distanceDiff,
            status = status,
            summaryText = summary,
            detailText = detail
        )
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
