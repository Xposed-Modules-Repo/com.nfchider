package com.nfchider

import android.app.Application
import com.nfchider.location.ModuleService
import com.nfchider.location.SimConfigRepository
import org.osmdroid.config.Configuration
import java.io.File

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // BUG-11 fix: never let service-bridge setup take down app startup.
        runCatching { ModuleService.init() }
        // Persist a default config on first launch. Without this the module app
        // never writes location_config.json, so the ConfigProvider fallback (and
        // therefore every already-running hooked process) has nothing to read and
        // NFC hiding silently stays off until the user touches a switch.
        runCatching {
            val file = File(filesDir, SimConfigRepository.FILE_NAME)
            if (!file.exists()) {
                SimConfigRepository.save(this, SimConfigRepository.load(this))
            } else {
                ModuleService.pushJson(SimConfigRepository.load(this).toJson())
            }
        }
        // Keep osmdroid entirely inside the app's cache dir: no storage
        // permissions needed, tiles simply don't survive a cache wipe.
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
    }
}
