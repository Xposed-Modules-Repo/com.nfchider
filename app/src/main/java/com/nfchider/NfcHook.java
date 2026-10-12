package com.nfchider;

import android.content.pm.PackageManager;
import android.nfc.NfcAdapter;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

import com.nfchider.location.ConfigStore;

public class NfcHook extends XposedModule {

    private static final String TAG = "NfcHider";

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        if ("com.nfchider".equals(param.getPackageName())) return;
        try {
            ConfigStore.init(this);
        } catch (Throwable ignored) {
        }

        Log.i(TAG, "Hooking NFC for: " + param.getPackageName());

        if ("android".equals(param.getPackageName())) {
            hookSystemServerPackageManager(param);
        } else {
            hookNfcAdapter();
            hookPackageManager(param);
        }
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

    private void hookSystemServerPackageManager(PackageLoadedParam param) {
        try {
            ClassLoader cl = param.getDefaultClassLoader();
            Class<?> pmsClass = null;
            try {
                pmsClass = cl.loadClass("com.android.server.pm.PackageManagerService");
            } catch (Throwable ignored) {
            }
            if (pmsClass != null) {
                for (Method method : pmsClass.getDeclaredMethods()) {
                    if (method.getName().equals("hasSystemFeature")) {
                        hook(method).intercept(chain -> {
                            if (!ConfigStore.isHookEnabled()) {
                                return chain.proceed();
                            }
                            Object arg0 = chain.getArg(0);
                            if (arg0 instanceof String) {
                                String feature = (String) arg0;
                                if (feature.toLowerCase().contains("nfc")) {
                                    return false;
                                }
                            }
                            return chain.proceed();
                        });
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "hookSystemServerPackageManager failed: " + t);
        }
    }
}
