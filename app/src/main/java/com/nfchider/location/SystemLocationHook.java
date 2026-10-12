package com.nfchider.location;

import android.location.Location;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

/**
 * Global system-level location hooks installed inside {@code system_server} (package: "android").
 *
 * Intercepts location updates at the framework service level so that all applications
 * across the entire device receive the simulated location without needing per-app scoping.
 *
 * When simulation is disabled in the app (or when the caller is com.nfchider), all
 * requests pass through untouched.
 */
public final class SystemLocationHook {

    private static final String TAG = "NfcHider/SysLoc";
    private static final Object INSTALL_LOCK = new Object();
    private static volatile boolean installed = false;

    private SystemLocationHook() {
    }

    public static void install(@NonNull XposedInterface api, @NonNull PackageLoadedParam param) {
        synchronized (INSTALL_LOCK) {
            if (installed) return;
            installed = true;
        }

        try {
            ConfigStore.init(api);
        } catch (Throwable t) {
            Log.w(TAG, "ConfigStore.init failed in system_server: " + t);
        }

        ClassLoader cl = param.getDefaultClassLoader();
        hookLocationManagerService(api, cl);
        hookLocationProviderManager(api, cl);
        Log.i(TAG, "SystemLocationHook installed in system_server");
    }

    /**
     * Hooks LocationManagerService (responsible for getLastLocation and IPC location queries).
     */
    private static void hookLocationManagerService(XposedInterface api, ClassLoader cl) {
        Class<?> lmsClass = null;
        String[] candidateClasses = new String[]{
                "com.android.server.LocationManagerService",
                "com.android.server.location.LocationManagerService"
        };
        for (String className : candidateClasses) {
            try {
                lmsClass = cl.loadClass(className);
                if (lmsClass != null) break;
            } catch (Throwable ignored) {
            }
        }
        if (lmsClass == null) {
            Log.w(TAG, "Could not find LocationManagerService in system_server");
            return;
        }

        // Hook all overloads of getLastLocation
        for (Method m : lmsClass.getDeclaredMethods()) {
            if ("getLastLocation".equals(m.getName())) {
                try {
                    api.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                        if (!ConfigStore.isSimActive()) {
                            return chain.proceed();
                        }
                        // Check if the caller is com.nfchider to preserve real GPS for this app
                        List<Object> args = chain.getArgs();
                        for (Object arg : args) {
                            if ("com.nfchider".equals(arg)) {
                                return chain.proceed();
                            }
                        }

                        TrajectoryConfig cfg = ConfigStore.get();
                        if (cfg == null || !cfg.enabled || !cfg.hasRoute()) {
                            return chain.proceed();
                        }

                        // Determine provider name if present in args
                        String provider = "gps";
                        for (Object arg : args) {
                            if (arg instanceof String && !((String) arg).contains(".")) {
                                provider = (String) arg;
                                break;
                            }
                        }
                        return LocationSim.buildLocation(cfg, provider);
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "Hook getLastLocation failed: " + t);
                }
            }
        }
    }

    /**
     * Hooks LocationProviderManager (Android 12+) onReportLocation to rewrite streaming updates.
     */
    private static void hookLocationProviderManager(XposedInterface api, ClassLoader cl) {
        Class<?> lpmClass = null;
        String[] candidateClasses = new String[]{
                "com.android.server.location.provider.LocationProviderManager",
                "com.android.server.LocationProviderManager"
        };
        for (String className : candidateClasses) {
            try {
                lpmClass = cl.loadClass(className);
                if (lpmClass != null) break;
            } catch (Throwable ignored) {
            }
        }
        if (lpmClass == null) {
            return;
        }

        for (Method m : lpmClass.getDeclaredMethods()) {
            if ("onReportLocation".equals(m.getName())) {
                try {
                    api.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                        if (!ConfigStore.isSimActive()) {
                            return chain.proceed();
                        }
                        TrajectoryConfig cfg = ConfigStore.get();
                        if (cfg == null || !cfg.enabled || !cfg.hasRoute()) {
                            return chain.proceed();
                        }

                        Object arg0 = chain.getArg(0);
                        if (arg0 != null) {
                            applySimToLocationResult(arg0, cfg);
                        }
                        return chain.proceed();
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "Hook onReportLocation failed: " + t);
                }
            }
        }
    }

    /**
     * Rewrites location items within LocationResult to match the trajectory position.
     */
    private static void applySimToLocationResult(Object locationResult, TrajectoryConfig cfg) {
        try {
            Class<?> clazz = locationResult.getClass();
            Method asListMethod = null;
            try {
                asListMethod = clazz.getMethod("asList");
            } catch (NoSuchMethodException e) {
                try {
                    asListMethod = clazz.getMethod("getLocations");
                } catch (NoSuchMethodException ignored) {
                }
            }

            TrajectoryEngine.Position pos = TrajectoryEngine.positionAt(cfg, System.currentTimeMillis());

            if (asListMethod != null) {
                Object listObj = asListMethod.invoke(locationResult);
                if (listObj instanceof List<?>) {
                    List<?> list = (List<?>) listObj;
                    for (Object item : list) {
                        if (item instanceof Location) {
                            mutateLocation((Location) item, pos, cfg);
                        }
                    }
                    return;
                }
            }

            // Fallback: try getLastLocation()
            Method getLastMethod = clazz.getMethod("getLastLocation");
            Object locObj = getLastMethod.invoke(locationResult);
            if (locObj instanceof Location) {
                mutateLocation((Location) locObj, pos, cfg);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void mutateLocation(Location loc, TrajectoryEngine.Position pos, TrajectoryConfig cfg) {
        loc.setLatitude(pos.lat);
        loc.setLongitude(pos.lng);
        if (!Double.isNaN(pos.ele)) {
            loc.setAltitude(pos.ele);
        }
        loc.setSpeed((float) pos.speedMps);
        loc.setBearing((float) pos.bearingDeg);
        loc.setAccuracy((float) cfg.accuracyM);
        loc.setTime(System.currentTimeMillis());
        loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
    }
}

