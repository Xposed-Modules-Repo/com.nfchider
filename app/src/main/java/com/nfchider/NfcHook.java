package com.nfchider;

import android.content.pm.PackageManager;
import android.nfc.NfcAdapter;
import android.nfc.NfcManager;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;

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
                    hook(method).intercept(chain -> {
                        if (!ConfigStore.isHookEnabled()) {
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
                    hook(method).intercept(chain -> {
                        if (!ConfigStore.isHookEnabled()) {
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
                if (method.getName().equals("hasSystemFeature")) {
                    hook(method).intercept(chain -> {
                        if (!ConfigStore.isHookEnabled()) {
                            return chain.proceed();
                        }
                        String feature = (String) chain.getArg(0);
                        if (feature != null && feature.toLowerCase().contains("nfc")) {
                            Log.i(TAG, "Blocked hasSystemFeature(" + feature + ")");
                            return false;
                        }
                        return chain.proceed();
                    });
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "hookPackageManager failed: " + t);
        }
    }
}
