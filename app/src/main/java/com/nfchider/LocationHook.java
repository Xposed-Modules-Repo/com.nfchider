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

    /**
     * Diagnostic probe: returns false by default, hooked by LocationSim to return true.
     */
    public static boolean isHookActive() {
        return false;
    }

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        LocationSim.install(this, param);
    }
}
