package com.nfchider.location;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;

import io.github.libxposed.api.XposedInterface;

/**
 * Hook-side access to the simulation config written by the module app.
 *
 * Two channels, in order of preference:
 * <ol>
 *     <li>libxposed remote preferences (push-updated via the framework)</li>
 *     <li>the module app's exported {@link ConfigProvider} (polled)</li>
 * </ol>
 * When both fail the cache keeps its last value; a process that never saw a
 * config simply behaves as if the simulation were disabled.
 */
final class ConfigStore {

    private static final String TAG = "NfcHider/Loc";

    private static final String PREFS_GROUP = "location";
    private static final String KEY_JSON = "config_json";
    private static final String PROVIDER_URI = "content://com.nfchider.location";
    private static final long FRESH_MS = 3000L;

    private static final Object LOCK = new Object();

    private static volatile boolean inited;
    private static volatile SharedPreferences remotePrefs;
    private static volatile Context appContext;
    private static volatile TrajectoryConfig cached;
    private static volatile long lastFetchElapsed = Long.MIN_VALUE;

    private ConfigStore() {
    }

    static void init(XposedInterface api) {
        if (inited) return;
        inited = true;
        try {
            SharedPreferences prefs = api.getRemotePreferences(PREFS_GROUP);
            prefs.registerOnSharedPreferenceChangeListener((p, key) -> {
                if (KEY_JSON.equals(key)) readRemoteIntoCache(prefs);
            });
            remotePrefs = prefs;
            readRemoteIntoCache(prefs);
        } catch (Throwable t) {
            // Framework without remote preferences support; fall back to the provider.
            remotePrefs = null;
        }
    }

    /** Called from the ContextWrapper.attachBaseContext hook; supplies a Context for provider reads. */
    static void onContextAvailable(Context context) {
        if (context != null && appContext == null) {
            appContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        }
    }

    /** Current config, or null when unavailable/disabled. Never throws. */
    static TrajectoryConfig get() {
        TrajectoryConfig c = cached;
        if (c != null && SystemClock.elapsedRealtime() - lastFetchElapsed < FRESH_MS) return c;
        synchronized (LOCK) {
            if (cached != null && SystemClock.elapsedRealtime() - lastFetchElapsed < FRESH_MS) return cached;
            refresh();
            return cached;
        }
    }

    /** True when a config was ever successfully loaded. */
    static boolean haveConfig() {
        return cached != null;
    }

    private static void readRemoteIntoCache(SharedPreferences prefs) {
        try {
            String json = prefs.getString(KEY_JSON, null);
            TrajectoryConfig cfg = TrajectoryConfig.fromJson(json);
            if (cfg != null) {
                cached = cfg;
                lastFetchElapsed = SystemClock.elapsedRealtime();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void refresh() {
        lastFetchElapsed = SystemClock.elapsedRealtime();
        if (remotePrefs != null) {
            readRemoteIntoCache(remotePrefs);
            if (cached != null) return;
        }
        Context ctx = appContext;
        if (ctx != null) {
            try {
                Bundle b = ctx.getContentResolver().call(
                        Uri.parse(PROVIDER_URI), "get", null, null);
                if (b != null) {
                    TrajectoryConfig cfg = TrajectoryConfig.fromJson(b.getString("config"));
                    if (cfg != null) cached = cfg;
                }
            } catch (Throwable ignored) {
                // Module app gone or provider not visible from this process; keep last value.
            }
        }
    }
}
