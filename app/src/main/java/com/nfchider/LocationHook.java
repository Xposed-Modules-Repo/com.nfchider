package com.nfchider;

import androidx.annotation.NonNull;

import com.nfchider.location.LocationSim;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

/**
 * Xposed entry for the location simulation feature. Registered in
 * {@code META-INF/xposed/java_init.list} alongside {@link NfcHook}.
 */
public class LocationHook extends XposedModule {

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        try {
            if (getModuleApplicationInfo().packageName.equals(param.getPackageName())) return;
        } catch (Throwable ignored) {
        }
        LocationSim.install(this, param);
    }
}
