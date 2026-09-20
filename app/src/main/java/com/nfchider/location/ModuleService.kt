package com.nfchider.location

import android.content.Context
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.io.File

/**
 * App-side bridge to the libxposed service: pushes the simulation config into
 * remote preferences so hooked processes receive (push-)updated values.
 * When no framework service is bound, only the local file copy is written and
 * the exported [ConfigProvider] acts as the fallback channel.
 */
object ModuleService {

    const val PREFS_GROUP = "location"
    const val KEY_JSON = "config_json"

    @Volatile
    var service: XposedService? = null
        private set

    @Volatile
    var bound: Boolean = false
        private set

    @Volatile
    private var initialized = false

    private var pendingJson: String? = null

    fun init() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
        }
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(svc: XposedService) {
                service = svc
                bound = true
                pendingJson?.let { pushJson(it) }
            }

            override fun onServiceDied(svc: XposedService) {
                service = null
                bound = false
            }
        })
    }

    fun pushJson(json: String) {
        pendingJson = json
        runCatching {
            service?.getRemotePreferences(PREFS_GROUP)?.edit()
                ?.putString(KEY_JSON, json)?.commit()
        }
    }
}

/**
 * Persistence for the location simulation config: a JSON file in the app's
 * private storage. The same JSON is served by [ConfigProvider] and pushed to
 * remote preferences via [ModuleService].
 */
object SimConfigRepository {

    const val FILE_NAME = "location_config.json"

    fun load(context: Context): TrajectoryConfig {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) {
            runCatching {
                TrajectoryConfig.fromJson(file.readText())?.let { return it }
            }
        }
        return TrajectoryConfig.builder().build()
    }

    fun save(context: Context, config: TrajectoryConfig) {
        runCatching {
            File(context.filesDir, FILE_NAME).writeText(config.toJson())
        }
        ModuleService.pushJson(config.toJson())
    }
}
