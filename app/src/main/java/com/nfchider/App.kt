package com.nfchider

import android.app.Application
import com.nfchider.location.ModuleService
import com.nfchider.location.SimConfigRepository
import java.io.File

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // Never let service-bridge setup take down app startup.
        runCatching { ModuleService.init() }
        // Persist a default config on first launch. Without this the module app
        // never writes the config file, so the ConfigProvider fallback (and
        // therefore every already-running hooked process) has nothing to read.
        // A fresh install must hide NFC without the user touching a switch.
        runCatching {
            val file = File(filesDir, SimConfigRepository.FILE_NAME)
            if (!file.exists()) {
                SimConfigRepository.save(this, SimConfigRepository.load(this))
            } else {
                ModuleService.pushJson(SimConfigRepository.load(this).toJson())
            }
        }
    }
}
