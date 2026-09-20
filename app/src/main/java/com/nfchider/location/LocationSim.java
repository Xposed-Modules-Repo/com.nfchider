package com.nfchider.location;

import android.app.PendingIntent;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.ExceptionMode;

/**
 * Installs the location simulation hooks into a target process.
 *
 * When the simulation is inactive every hook is a pass-through; the target
 * app behaves exactly as before. When active, fixes are computed from
 * {@link TrajectoryEngine} and delivered on the thread the app would have
 * received them on (its registration Looper / Executor).
 */
public final class LocationSim {

    private static final String TAG = "NfcHider/Loc";
    private static final Object INSTALL_LOCK = new Object();
    private static volatile boolean installed;

    private static final int K_NONE = 0;
    private static final int K_LAST_KNOWN = 1;
    private static final int K_GET_CURRENT = 2;
    private static final int K_REQ_UPDATES_LISTENER = 3;
    private static final int K_REQ_UPDATES_PI = 4;
    private static final int K_SINGLE_UPDATE_LISTENER = 5;
    private static final int K_SINGLE_UPDATE_PI = 6;
    private static final int K_REMOVE_UPDATES = 7;
    private static final int K_IS_PROVIDER_ENABLED = 8;
    private static final int K_IS_LOCATION_ENABLED = 9;
    private static final int K_GET_PROVIDERS = 10;
    private static final int K_GET_BEST_PROVIDER = 11;
    private static final int K_IS_MOCK = 12;

    /** Recurring delivery target. */
    interface Delivery {
        void deliver(Location location) throws Exception;
    }

    private static final Map<Object, Runnable> LOOPS = new ConcurrentHashMap<>();
    private static volatile HandlerThread simThread;
    private static volatile Handler simHandler;
    private static volatile Handler mainHandler;

    private LocationSim() {
    }

    public static void install(XposedInterface api, PackageLoadedParam param) {
        synchronized (INSTALL_LOCK) {
            if (installed) return;
            installed = true;
        }
        try {
            ConfigStore.init(api);
        } catch (Throwable t) {
            log(api, "config init failed: " + t);
        }
        try {
            hookContextAttach(api);
        } catch (Throwable t) {
            log(api, "context hook failed: " + t);
        }
        try {
            hookLocationManager(api);
        } catch (Throwable t) {
            log(api, "LocationManager hook failed: " + t);
        }
        try {
            hookLocationClass(api);
        } catch (Throwable t) {
            log(api, "Location hook failed: " + t);
        }
        try {
            FusedHook.install(api, param.getDefaultClassLoader());
        } catch (Throwable t) {
            log(api, "fused hook failed: " + t);
        }
        log(api, "location simulation hooks installed");
    }

    // ---------------------------------------------------------------------
    // Hook installation
    // ---------------------------------------------------------------------

    /** Capture an app Context (for the ContentProvider config fallback). */
    private static void hookContextAttach(XposedInterface api) throws Exception {
        Method m = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
        api.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
            try {
                Object arg = chain.getArg(0);
                if (arg instanceof Context) ConfigStore.onContextAvailable((Context) arg);
            } catch (Throwable ignored) {
            }
            return chain.proceed();
        });
    }

    private static void hookLocationManager(XposedInterface api) {
        for (Method m : LocationManager.class.getDeclaredMethods()) {
            int kind = classifyLocationManagerMethod(m);
            if (kind == K_NONE) continue;
            try {
                api.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> handleLocationManager(chain, kind));
            } catch (Throwable t) {
                log(api, "hook " + m + " failed: " + t);
            }
        }
    }

    private static int classifyLocationManagerMethod(Method m) {
        String n = m.getName();
        Class<?>[] p = m.getParameterTypes();
        boolean listener = p.length > 0 && p[p.length - 1] == LocationListener.class;
        boolean pi = p.length > 0 && p[p.length - 1] == PendingIntent.class;
        switch (n) {
            case "getLastKnownLocation":
                return p.length == 1 && p[0] == String.class ? K_LAST_KNOWN : K_NONE;
            case "getCurrentLocation":
                for (Class<?> c : p) if (c == Consumer.class) return K_GET_CURRENT;
                return K_NONE;
            case "requestLocationUpdates":
                if (listener) return K_REQ_UPDATES_LISTENER;
                if (pi) return K_REQ_UPDATES_PI;
                return K_NONE;
            case "requestSingleUpdate":
                for (Class<?> c : p) if (c == LocationListener.class) return K_SINGLE_UPDATE_LISTENER;
                for (Class<?> c : p) if (c == PendingIntent.class) return K_SINGLE_UPDATE_PI;
                return K_NONE;
            case "removeUpdates":
                return (p.length == 1 && (p[0] == LocationListener.class || p[0] == PendingIntent.class))
                        ? K_REMOVE_UPDATES : K_NONE;
            case "isProviderEnabled":
                return p.length == 1 && p[0] == String.class ? K_IS_PROVIDER_ENABLED : K_NONE;
            case "isLocationEnabled":
                return p.length == 0 ? K_IS_LOCATION_ENABLED : K_NONE;
            case "getProviders":
                return p.length == 1 && p[0] == boolean.class ? K_GET_PROVIDERS : K_NONE;
            case "getBestProvider":
                return p.length == 2 ? K_GET_BEST_PROVIDER : K_NONE;
            default:
                return K_NONE;
        }
    }

    private static void hookLocationClass(XposedInterface api) {
        for (Method m : Location.class.getDeclaredMethods()) {
            String n = m.getName();
            if (m.getParameterTypes().length != 0) continue;
            if (!("isFromMockProvider".equals(n) || "isMock".equals(n))) continue;
            try {
                api.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            if (isSimActive()) return Boolean.FALSE;
                            return chain.proceed();
                        });
            } catch (Throwable t) {
                log(api, "hook Location." + n + " failed: " + t);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Interception
    // ---------------------------------------------------------------------

    private static boolean isSimActive() {
        TrajectoryConfig cfg = ConfigStore.get();
        return cfg != null && cfg.enabled && cfg.hasRoute();
    }

    private static Object handleLocationManager(Chain chain, int kind) throws Throwable {
        switch (kind) {
            case K_LAST_KNOWN: {
                Object provider = chain.getArg(0);
                TrajectoryConfig cfg = ConfigStore.get();
                if (provider == null || cfg == null || !cfg.enabled || !cfg.hasRoute()) {
                    return chain.proceed();
                }
                return buildLocation(cfg, (String) provider);
            }
            case K_GET_CURRENT: {
                TrajectoryConfig cfg = ConfigStore.get();
                if (cfg == null || !cfg.enabled || !cfg.hasRoute()) return chain.proceed();
                List<Object> args = chain.getArgs();
                String provider = args.get(0) instanceof String ? (String) args.get(0) : "gps";
                Consumer<Location> consumer = null;
                Executor executor = null;
                Looper looper = null;
                for (Object a : args) {
                    if (a instanceof Consumer && consumer == null) consumer = (Consumer<Location>) a;
                    else if (a instanceof Executor && executor == null) executor = (Executor) a;
                    else if (a instanceof Looper && looper == null) looper = (Looper) a;
                }
                if (consumer == null) return chain.proceed();
                deliverOnce(consumer, executor, looper, provider);
                return null;
            }
            case K_REQ_UPDATES_LISTENER: {
                TrajectoryConfig cfg = ConfigStore.get();
                if (cfg == null || !cfg.enabled || !cfg.hasRoute()) return chain.proceed();
                List<Object> args = chain.getArgs();
                LocationListener listener = (LocationListener) args.get(args.size() - 1);
                String provider = args.get(0) instanceof String ? (String) args.get(0) : LocationManager.GPS_PROVIDER;
                Executor executor = null;
                Looper looper = null;
                for (Object a : args) {
                    if (a instanceof Executor && executor == null) executor = (Executor) a;
                    else if (a instanceof Looper && looper == null) looper = (Looper) a;
                }
                if (looper == null) looper = Looper.myLooper();
                deliverToListener(listener, provider, executor, looper);
                return null;
            }
            case K_REQ_UPDATES_PI: {
                TrajectoryConfig cfg = ConfigStore.get();
                if (cfg == null || !cfg.enabled || !cfg.hasRoute()) return chain.proceed();
                List<Object> args = chain.getArgs();
                PendingIntent pi = (PendingIntent) args.get(args.size() - 1);
                String provider = args.get(0) instanceof String ? (String) args.get(0) : LocationManager.GPS_PROVIDER;
                startLoop(pi, provider, loc -> sendViaPendingIntent(pi, loc));
                return null;
            }
            case K_SINGLE_UPDATE_LISTENER: {
                TrajectoryConfig cfg = ConfigStore.get();
                if (cfg == null || !cfg.enabled || !cfg.hasRoute()) return chain.proceed();
                List<Object> args = chain.getArgs();
                LocationListener listener = null;
                Looper looper = null;
                for (Object a : args) {
                    if (a instanceof LocationListener && listener == null) listener = (LocationListener) a;
                    else if (a instanceof Looper && looper == null) looper = (Looper) a;
                }
                if (listener == null) return chain.proceed();
                if (looper == null) looper = Looper.myLooper();
                String provider = args.get(0) instanceof String ? (String) args.get(0) : LocationManager.GPS_PROVIDER;
                Location loc = buildLocation(cfg, provider);
                final LocationListener lis = listener;
                Handler h = new Handler(looper != null ? looper : Looper.getMainLooper());
                h.post(() -> {
                    try {
                        lis.onLocationChanged(loc);
                    } catch (Throwable ignored) {
                    }
                });
                return null;
            }
            case K_SINGLE_UPDATE_PI: {
                TrajectoryConfig cfg = ConfigStore.get();
                if (cfg == null || !cfg.enabled || !cfg.hasRoute()) return chain.proceed();
                List<Object> args = chain.getArgs();
                PendingIntent pi = null;
                String provider = LocationManager.GPS_PROVIDER;
                for (Object a : args) {
                    if (a instanceof PendingIntent) pi = (PendingIntent) a;
                    else if (a instanceof String) provider = (String) a;
                }
                if (pi == null) return chain.proceed();
                sendViaPendingIntent(pi, buildLocation(cfg, provider));
                return null;
            }
            case K_REMOVE_UPDATES: {
                Object target = chain.getArg(0);
                if (target != null) stopLoop(target);
                return chain.proceed();
            }
            case K_IS_PROVIDER_ENABLED: {
                if (isSimActive()) return Boolean.TRUE;
                return chain.proceed();
            }
            case K_IS_LOCATION_ENABLED: {
                if (isSimActive()) return Boolean.TRUE;
                return chain.proceed();
            }
            case K_GET_PROVIDERS: {
                @SuppressWarnings("unchecked")
                List<String> result = (List<String>) chain.proceed();
                if (isSimActive() && result != null && !result.contains(LocationManager.GPS_PROVIDER)) {
                    List<String> copy = new ArrayList<>(result);
                    copy.add(LocationManager.GPS_PROVIDER);
                    return copy;
                }
                return result;
            }
            case K_GET_BEST_PROVIDER: {
                Object result = chain.proceed();
                if (isSimActive() && result == null) return LocationManager.GPS_PROVIDER;
                return result;
            }
            default:
                return chain.proceed();
        }
    }

    // ---------------------------------------------------------------------
    // Delivery machinery
    // ---------------------------------------------------------------------

    private static void deliverToListener(LocationListener listener, String provider,
                                          Executor executor, Looper looper) {
        Delivery delivery;
        if (executor != null) {
            delivery = loc -> executor.execute(() -> {
                try {
                    listener.onLocationChanged(loc);
                } catch (Throwable ignored) {
                }
            });
        } else {
            Handler h = new Handler(looper != null ? looper : Looper.getMainLooper());
            delivery = loc -> h.post(() -> {
                try {
                    listener.onLocationChanged(loc);
                } catch (Throwable ignored) {
                }
            });
        }
        startLoop(listener, provider, delivery);
    }

    private static void sendViaPendingIntent(PendingIntent pi, Location loc) throws Exception {
        Intent i = new Intent();
        i.putExtra(LocationManager.KEY_LOCATION_CHANGED, loc);
        pi.send(null, 0, i);
    }

    /** Starts (or replaces) the recurring update loop for a registration key. */
    static void startLoop(Object key, String provider, Delivery delivery) {
        stopLoop(key);
        Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            try {
                if (LOOPS.get(key) != tick[0]) return;
                TrajectoryConfig cfg = ConfigStore.get();
                if (cfg != null) {
                    if (!cfg.enabled || !cfg.hasRoute()) {
                        stopLoop(key);
                        return;
                    }
                    delivery.deliver(buildLocation(cfg, provider));
                }
                // cfg == null: config temporarily unavailable, retry next tick
            } catch (Throwable ignored) {
            }
            if (LOOPS.get(key) == tick[0]) {
                long interval = 1000L;
                TrajectoryConfig cfg = ConfigStore.get();
                if (cfg != null && cfg.updateIntervalMs > 0) interval = cfg.updateIntervalMs;
                simHandler().postDelayed(tick[0], interval);
            }
        };
        LOOPS.put(key, tick[0]);
        simHandler().post(tick[0]);
    }

    static void stopLoop(Object key) {
        Runnable r = LOOPS.remove(key);
        if (r != null) simHandler().removeCallbacks(r);
    }

    private static Handler simHandler() {
        Handler h = simHandler;
        if (h == null) {
            synchronized (LocationSim.class) {
                if (simHandler == null) {
                    HandlerThread t = new HandlerThread("nfchider-locsim");
                    t.start();
                    simHandler = new Handler(t.getLooper());
                }
                h = simHandler;
            }
        }
        return h;
    }

    private static void deliverOnce(Consumer<Location> consumer, Executor executor, Looper looper,
                                    String provider) {
        if (executor != null) {
            executor.execute(() -> {
                try {
                    Location loc = buildLocationFor(provider);
                    if (loc != null) consumer.accept(loc);
                } catch (Throwable ignored) {
                }
            });
        } else {
            Handler h = new Handler(looper != null ? looper : Looper.getMainLooper());
            h.post(() -> {
                try {
                    Location loc = buildLocationFor(provider);
                    if (loc != null) consumer.accept(loc);
                } catch (Throwable ignored) {
                }
            });
        }
    }

    private static Location buildLocationFor(String provider) {
        TrajectoryConfig cfg = ConfigStore.get();
        if (cfg == null) return null;
        return buildLocation(cfg, provider != null ? provider : LocationManager.GPS_PROVIDER);
    }

    // ---------------------------------------------------------------------
    // Location factory
    // ---------------------------------------------------------------------

    static Location buildLocation(TrajectoryConfig cfg, String provider) {
        TrajectoryEngine.Position p = TrajectoryEngine.positionAt(cfg, System.currentTimeMillis());
        Location l = new Location(provider != null ? provider : LocationManager.GPS_PROVIDER);
        l.setLatitude(p.lat);
        l.setLongitude(p.lng);
        l.setAltitude(p.ele);
        l.setAccuracy((float) cfg.accuracyM);
        l.setSpeed((float) p.speedMps);
        l.setBearing((float) p.bearingDeg);
        l.setTime(System.currentTimeMillis());
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                l.setVerticalAccuracyMeters((float) (cfg.accuracyM * 0.5));
                l.setSpeedAccuracyMetersPerSecond(Math.max(0.3f, (float) (p.speedMps * 0.1 + 0.3)));
                l.setBearingAccuracyDegrees(5f);
            } catch (Throwable ignored) {
            }
        }
        try {
            Bundle extras = new Bundle();
            extras.putInt("satellites", 11);
            l.setExtras(extras);
        } catch (Throwable ignored) {
        }
        return l;
    }

    static void log(XposedInterface api, String msg) {
        try {
            api.log(Log.INFO, TAG, msg);
        } catch (Throwable ignored) {
        }
    }
}
