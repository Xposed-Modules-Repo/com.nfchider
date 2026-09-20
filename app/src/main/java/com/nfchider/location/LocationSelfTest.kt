package com.nfchider.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Diagnostic utility that monitors simulation status and provides
 * clear guidance on LSPosed configuration without requiring the module
 * itself to be checked in LSPosed scopes.
 */
object LocationSelfTest {

    enum class TestStatus {
        BROADCASTING,       // Simulation is actively computing and broadcasting coordinates
        ROUTE_NOT_READY,    // Route has fewer than 2 points
        SIM_DISABLED        // Simulation toggle is turned off
    }

    data class DiagnosticReport(
        val isSimEnabled: Boolean,
        val hasRoute: Boolean,
        val isChannelBound: Boolean,
        val hasPermission: Boolean,
        val actualLocation: Location?,
        val expectedPos: TrajectoryEngine.Position?,
        val status: TestStatus,
        val summaryText: String,
        val detailText: String
    )

    fun runDiagnostics(context: Context, config: TrajectoryConfig): DiagnosticReport {
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

        val status: TestStatus
        val summary: String
        val detail: String

        when {
            !simEnabled -> {
                status = TestStatus.SIM_DISABLED
                summary = "模拟未开始"
                detail = "点击下方「开始模拟」按钮即可向目标应用广播模拟定位。"
            }
            !hasRoute -> {
                status = TestStatus.ROUTE_NOT_READY
                summary = "轨迹未就绪"
                detail = "当前轨迹点不足 2 个，请在地图上添加轨迹点或导入 GPX 文件。"
            }
            else -> {
                status = TestStatus.BROADCASTING
                summary = "正在广播模拟定位"
                val posStr = if (expectedPos != null) {
                    String.format("%.5f, %.5f", expectedPos.lat, expectedPos.lng)
                } else "计算中..."
                detail = "实时广播坐标: $posStr | 速度: ${String.format("%.1f", config.speedMps)} m/s"
            }
        }

        return DiagnosticReport(
            isSimEnabled = simEnabled,
            hasRoute = hasRoute,
            isChannelBound = channelBound,
            hasPermission = hasPermission,
            actualLocation = actualLoc,
            expectedPos = expectedPos,
            status = status,
            summaryText = summary,
            detailText = detail
        )
    }
}
