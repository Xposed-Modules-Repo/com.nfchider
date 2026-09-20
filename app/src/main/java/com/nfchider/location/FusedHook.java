package com.nfchider.location;

import android.location.Location;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.ExceptionMode;

/**
 * Best-effort hooks for Google Play services' {@code FusedLocationProviderClient}.
 *
 * The client classes live in the target app's own dex, so they can be loaded
 * from the default classloader. Results are fabricated via the public
 * {@code TaskCompletionSource} API; callbacks receive a {@code LocationResult}
 * built with {@code LocationResult.create(...)}.
 *
 * If any of the required classes is missing (old play services, R8-stripped
 * app, no play services at all), fused support is silently skipped and only
 * the framework {@code LocationManager} hooks remain active.
 */
final class FusedHook {

    private static final String CLIENT_CLASS = "com.google.android.gms.location.FusedLocationProviderClient";
    private static final String TASK_CLASS = "com.google.android.gms.tasks.Task";
    private static final String TCS_CLASS = "com.google.android.gms.tasks.TaskCompletionSource";
    private static final String LOC_RESULT_CLASS = "com.google.android.gms.location.LocationResult";
    private static final String LOC_CALLBACK_CLASS = "com.google.android.gms.location.LocationCallback";
    private static final String GMS_LOC_LISTENER_CLASS = "com.google.android.gms.location.LocationListener";

    private static Constructor<?> tcsCtor;
    private static Method tcsSetResult;
    private static Method tcsGetTask;
    private static Method locResultCreate;
    private static Class<?> locCallbackClass;
    private static Class<?> gmsLocListenerClass;

    private FusedHook() {
    }

    static void install(XposedInterface api, ClassLoader cl) {
        Class<?> client;
        Class<?> taskClass;
        Class<?> tcsClass;
        Class<?> locResultClass;
        try {
            client = cl.loadClass(CLIENT_CLASS);
            taskClass = cl.loadClass(TASK_CLASS);
            tcsClass = cl.loadClass(TCS_CLASS);
            locResultClass = cl.loadClass(LOC_RESULT_CLASS);
        } catch (Throwable t) {
            return; // app does not use play services location
        }
        try {
            tcsCtor = tcsClass.getConstructor();
            tcsSetResult = tcsClass.getMethod("setResult", Object.class);
            tcsGetTask = tcsClass.getMethod("getTask");
            locResultCreate = locResultClass.getMethod("create", List.class);
        } catch (Throwable t) {
            LocationSim.log(api, "fused reflection incomplete: " + t);
            return;
        }
        try {
            locCallbackClass = cl.loadClass(LOC_CALLBACK_CLASS);
        } catch (Throwable t) {
            locCallbackClass = null;
        }
        try {
            gmsLocListenerClass = cl.loadClass(GMS_LOC_LISTENER_CLASS);
        } catch (Throwable t) {
            gmsLocListenerClass = null;
        }

        int count = 0;
        for (Method m : client.getDeclaredMethods()) {
            if (!taskClass.isAssignableFrom(m.getReturnType())) continue;
            String kind = classifyFused(m);
            if (kind == null) continue;
            try {
                api.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> handleFused(api, chain, kind, locResultClass));
                count++;
            } catch (Throwable t) {
                LocationSim.log(api, "fused hook " + m.getName() + " failed: " + t);
            }
        }
        if (count > 0) LocationSim.log(api, "fused location hooks installed: " + count);
    }

    private static String classifyFused(Method m) {
        String n = m.getName();
        Class<?>[] p = m.getParameterTypes();
        switch (n) {
            case "getLastLocation":
                return "last";
            case "getCurrentLocation":
                return "current";
            case "requestLocationUpdates":
                if (hasParam(p, LOC_CALLBACK_CLASS)) return "req_cb";
                if (hasParam(p, GMS_LOC_LISTENER_CLASS)) return "req_lis";
                if (hasParam(p, "android.location.LocationListener")) return "req_lis";
                return null; // PendingIntent variant: left untouched
            case "removeLocationUpdates":
                if (hasParam(p, LOC_CALLBACK_CLASS) || hasParam(p, GMS_LOC_LISTENER_CLASS)
                        || hasParam(p, "android.location.LocationListener")) return "remove";
                return null;
            case "flushLocations":
                return "void_task";
            default:
                if (n.startsWith("setMock")) return "void_task";
                return null;
        }
    }

    private static boolean hasParam(Class<?>[] params, String className) {
        for (Class<?> c : params) {
            if (c.getName().equals(className)) return true;
        }
        return false;
    }

    private static boolean isLocationCallback(Object a) {
        if (locCallbackClass != null && locCallbackClass.isInstance(a)) return true;
        return implementsNamedInterface(a.getClass(), "LocationCallback");
    }

    private static boolean isGmsLocationListener(Object a) {
        if (gmsLocListenerClass != null && gmsLocListenerClass.isInstance(a)) return true;
        return implementsNamedInterface(a.getClass(), "LocationListener")
                && !(a instanceof android.location.LocationListener);
    }

    /** True when the class (or a supertype) implements an interface with the given simple name. */
    private static boolean implementsNamedInterface(Class<?> c, String simpleName) {
        while (c != null && c != Object.class) {
            for (Class<?> itf : c.getInterfaces()) {
                if (itf.getSimpleName().equals(simpleName)) return true;
                if (implementsNamedInterface(itf, simpleName)) return true;
            }
            c = c.getSuperclass();
        }
        return false;
    }

    private static Object handleFused(XposedInterface api, Chain chain, String kind,
                                      Class<?> locResultClass) throws Throwable {
        TrajectoryConfig cfg = ConfigStore.get();
        boolean active = cfg != null && cfg.enabled && cfg.hasRoute();
        List<Object> args = chain.getArgs();

        switch (kind) {
            case "last": {
                if (!active) return chain.proceed();
                return completedTask(LocationSim.buildLocation(cfg, "fused"));
            }
            case "current": {
                if (!active) return chain.proceed();
                Object receiver = findOutcomeReceiver(args);
                if (receiver != null) {
                    invokeOutcomeReceiver(receiver, args, LocationSim.buildLocation(cfg, "fused"));
                    return completedTask(null);
                }
                return completedTask(LocationSim.buildLocation(cfg, "fused"));
            }
            case "req_cb":
            case "req_lis": {
                if (!active) return chain.proceed();
                Object callback = null;
                Executor executor = null;
                Looper looper = null;
                for (Object a : args) {
                    if (a == null) continue;
                    if (callback == null && (isLocationCallback(a) || isGmsLocationListener(a)
                            || a instanceof android.location.LocationListener)) {
                        callback = a;
                    } else if (executor == null && a instanceof Executor) {
                        executor = (Executor) a;
                    } else if (looper == null && a instanceof Looper) {
                        looper = (Looper) a;
                    }
                }
                if (callback == null) return chain.proceed();
                final Object cb = callback;
                final boolean viaResult = isLocationCallback(cb);
                final Method delivery = findDeliveryMethod(cb, locResultClass);
                if (delivery == null) return chain.proceed();
                final Executor ex = executor;
                final Handler handler = executor == null
                        ? new Handler(looper != null ? looper : Looper.getMainLooper())
                        : null;
                LocationSim.startLoop(cb, "fused", loc -> {
                    Object payload;
                    if (viaResult) {
                        payload = locResultCreate.invoke(null, Collections.singletonList(loc));
                    } else {
                        payload = loc;
                    }
                    if (ex != null) {
                        ex.execute(() -> invokeQuiet(api, delivery, cb, payload));
                    } else {
                        handler.post(() -> invokeQuiet(api, delivery, cb, payload));
                    }
                });
                return completedTask(null);
            }
            case "remove": {
                if (!active) return chain.proceed();
                for (Object a : args) {
                    if (a != null && (isLocationCallback(a) || isGmsLocationListener(a)
                            || a instanceof android.location.LocationListener)) {
                        LocationSim.stopLoop(a);
                        break;
                    }
                }
                return completedTask(null);
            }
            case "void_task":
            default: {
                if (!active) return chain.proceed();
                return completedTask(null);
            }
        }
    }

    private static Method findDeliveryMethod(Object callback, Class<?> locResultClass) {
        try {
            if (isLocationCallback(callback)) {
                Method m = callback.getClass().getMethod("onLocationResult", locResultClass);
                m.setAccessible(true);
                return m;
            }
            Method m = callback.getClass().getMethod("onLocationChanged", Location.class);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void invokeQuiet(XposedInterface api, Method method, Object target, Object arg) {
        try {
            method.invoke(target, arg);
        } catch (Throwable t) {
            LocationSim.log(api, "fused callback failed: " + t);
        }
    }

    private static Object findOutcomeReceiver(List<Object> args) {
        for (Object a : args) {
            if (a != null && implementsNamedInterface(a.getClass(), "OutcomeReceiver")) return a;
        }
        return null;
    }

    private static void invokeOutcomeReceiver(Object receiver, List<Object> args, Location loc) {
        try {
            Method onResult = receiver.getClass().getMethod("onResult", Object.class);
            onResult.setAccessible(true);
            Executor executor = null;
            for (Object a : args) {
                if (a instanceof Executor) {
                    executor = (Executor) a;
                    break;
                }
            }
            if (executor != null) {
                executor.execute(() -> {
                    try {
                        onResult.invoke(receiver, loc);
                    } catch (Throwable ignored) {
                    }
                });
            } else {
                onResult.invoke(receiver, loc);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Object completedTask(Object value) throws Exception {
        Object tcs = tcsCtor.newInstance();
        tcsSetResult.invoke(tcs, value);
        return tcsGetTask.invoke(tcs);
    }
}
