package com.nfchider;

import android.content.pm.FeatureInfo;
import android.nfc.NfcAdapter;
import android.nfc.NfcManager;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedModule;

import com.nfchider.location.ConfigStore;

public class NfcHook extends XposedModule {

    private static final String TAG = "NfcHider";

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        try {
            ConfigStore.init(this);
        } catch (Throwable ignored) {
        }

        Log.i(TAG, "Hooking NFC for: " + param.getPackageName());

        hookNfcAdapter();
        hookNfcManager();
        hookPackageManager(param);
    }

    private void hookNfcAdapter() {
        try {
            for (Method method : NfcAdapter.class.getDeclaredMethods()) {
                if (method.getReturnType().equals(NfcAdapter.class)
                        && java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                    hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                        if (!ConfigStore.isNfcHidden()) {
                            return chain.proceed();
                        }
                        Log.i(TAG, "Blocked: " + chain.getExecutable().getName());
                        return null;
                    });
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "hookNfcAdapter failed: " + t);
        }
    }

    /**
     * BUG-07 fix: {@code NfcManager.getDefaultAdapter()} is an instance method,
     * so the static-only sweep over {@link NfcAdapter} misses it. Kotlin's
     * {@code nfcManager.defaultAdapter} and many Java apps go through this path
     * and would still obtain a live adapter on NFC-capable devices.
     */
    private void hookNfcManager() {
        try {
            for (Method method : NfcManager.class.getDeclaredMethods()) {
                if (method.getReturnType().equals(NfcAdapter.class)
                        && method.getParameterTypes().length == 0
                        && !java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                    hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                        if (!ConfigStore.isNfcHidden()) {
                            return chain.proceed();
                        }
                        Log.i(TAG, "Blocked: NfcManager." + chain.getExecutable().getName());
                        return null;
                    });
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "hookNfcManager failed: " + t);
        }
    }

    private void hookPackageManager(PackageLoadedParam param) {
        try {
            Class<?> pmClass = param.getDefaultClassLoader()
                    .loadClass("android.app.ApplicationPackageManager");
            for (Method method : pmClass.getDeclaredMethods()) {
                if (!isFeatureQuery(method)) continue;
                String name = method.getName();
                hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                    if (!ConfigStore.isNfcHidden()) {
                        return chain.proceed();
                    }
                    if (name.equals("hasSystemFeature")) {
                        Object arg0 = chain.getArg(0);
                        if (arg0 instanceof String
                                && ((String) arg0).toLowerCase().contains("nfc")) {
                            Log.i(TAG, "Blocked hasSystemFeature(" + arg0 + ")");
                            return false;
                        }
                        return chain.proceed();
                    }
                    // getSystemAvailableFeatures(): filter NFC out of the array.
                    return stripNfcFeatures(chain.proceed());
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "hookPackageManager failed: " + t);
        }
    }

    /**
     * Feature-query entry points worth intercepting.
     *
     * <p>{@code hasSystemFeature} covers the direct question ("do you have NFC?"),
     * but apps that enumerate hardware call {@code getSystemAvailableFeatures()}.
     * Hiding only the former left the feature plainly visible in the latter.
     */
    private static boolean isFeatureQuery(Method method) {
        String n = method.getName();
        if (n.equals("hasSystemFeature")) return true;
        if (n.equals("getSystemAvailableFeatures")) {
            // FeatureInfo[] getSystemAvailableFeatures()
            return method.getParameterTypes().length == 0;
        }
        return false;
    }

    /** Drops every NFC FeatureInfo from a {@code getSystemAvailableFeatures()} result. */
    private static Object stripNfcFeatures(Object result) {
        if (!(result instanceof FeatureInfo[])) return result;
        FeatureInfo[] in = (FeatureInfo[]) result;
        List<FeatureInfo> out = new ArrayList<>();
        for (FeatureInfo fi : in) {
            if (fi == null) continue;
            if (fi.name != null && fi.name.toLowerCase().contains("nfc")) continue;
            out.add(fi);
        }
        return out.toArray(new FeatureInfo[0]);
    }
}
