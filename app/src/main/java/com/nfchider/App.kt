package com.nfchider

import android.app.Application
import com.nfchider.location.ModuleService
import org.osmdroid.config.Configuration
import java.io.File

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        ModuleService.init()
        // Keep osmdroid entirely inside the app's cache dir: no storage
        // permissions needed, tiles simply don't survive a cache wipe.
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
    }
}
