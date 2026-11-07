package com.nfchider.location;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;

import io.github.libxposed.api.XposedInterface;

/**
 * Hook-side access to the NFC-hiding config written by the module app.
 *
 * Two channels, in order of preference:
 * <ol>
 *     <li>libxposed remote preferences (push-updated via the framework)</li>
 *     <li>the module app's exported {@link ConfigProvider} (polled)</li>
 * </ol>
 * When both fail the cache keeps its last value; a process that never saw a
 * config simply hides NFC (the safe default for a module called "NFC Hider").
 */
public final class ConfigStore {

    private static final String PREFS_GROUP = "location";
    private static final String KEY_JSON = "config_json";
    private static final String PROVIDER_URI = "content://com.nfchider.location";
    private static final long FRESH_MS = 3000L;

    private static final Object LOCK = new Object();

    private static volatile boolean inited;
    private static volatile SharedPreferences remotePrefs;

    /**
     * Strong anchor for the remote-preferences listener registered in {@link #init}.
     *
     * <p>libxposed's {@code RemotePreferences} keeps listeners in a
     * {@link java.util.WeakHashMap}, so a listener that is not referenced
     * anywhere else may be garbage-collected at any time. Without this anchor
     * the push channel silently dies after a GC and switch changes never reach
     * hooked processes until they are restarted.
     */
    private static SharedPreferences.OnSharedPreferenceChangeListener listenerRef;

    private static volatile Context appContext;
    private static volatile TrajectoryConfig cached;
    private static volatile long lastFetchElapsed = Long.MIN_VALUE;

    private ConfigStore() {
    }

    public static void init(XposedInterface api) {
        if (inited) return;
        try {
            SharedPreferences prefs = api.getRemotePreferences(PREFS_GROUP);
            SharedPreferences.OnSharedPreferenceChangeListener l = (p, key) -> {
                if (KEY_JSON.equals(key)) readRemoteIntoCache(p);
            };
            listenerRef = l; // keep a strong reference; RemotePreferences holds listeners weakly
            prefs.registerOnSharedPreferenceChangeListener(l);
            remotePrefs = prefs;
            readRemoteIntoCache(prefs);
            inited = true;
        } catch (Throwable t) {
            // Framework without remote preferences support, or the binder simply
            // was not ready yet. Leave `inited` false so a later package load can
            // retry.
            remotePrefs = null;
        }
    }

    /** Called from the ContextWrapper.attachBaseContext hook; supplies a Context for provider reads. */
    public static void onContextAvailable(Context context) {
        if (context != null && appContext == null) {
            appContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        }
    }

    /** Current config, or null when unavailable. Never throws. */
    public static TrajectoryConfig get() {
        TrajectoryConfig c = cached;
        if (c != null && SystemClock.elapsedRealtime() - lastFetchElapsed < FRESH_MS) return c;
        synchronized (LOCK) {
            if (cached != null && SystemClock.elapsedRealtime() - lastFetchElapsed < FRESH_MS) return cached;
            refresh();
            return cached;
        }
    }

    /**
     * True when NFC hardware must be hidden from this process.
     *
     * <p>No config yet means the module app has never run: fail "hidden" rather
     * than "exposed", so the module works immediately after installation.
     */
    public static boolean isNfcHidden() {
        TrajectoryConfig c = get();
        if (c == null) return true;
        return c.masterOn && c.nfcHide;
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

        // 1. Push channel first — it is the cheap one and reflects live edits.
        TrajectoryConfig pushed = null;
        if (remotePrefs != null) pushed = readRemote(remotePrefs);

        // 2. Always consult the provider too, so a stale pushed value cannot mask
        //    the file the app actually wrote. The provider reads the module app's
        //    own file, so it wins whenever it has an answer.
        TrajectoryConfig fromProvider = null;
        Context ctx = appContext;
        if (ctx != null) {
            try {
                Bundle b = ctx.getContentResolver().call(
                        Uri.parse(PROVIDER_URI), "get", null, null);
                if (b != null) {
                    fromProvider = TrajectoryConfig.fromJson(b.getString("config"));
                }
            } catch (Throwable ignored) {
                // Module app gone or provider not visible from this process.
            }
        }

        if (fromProvider != null) cached = fromProvider;
        else if (pushed != null) cached = pushed;
        // else: keep the previous value; a null cache means "NFC hidden".
    }

    /** Reads the current value out of remote preferences without touching {@link #cached}. */
    private static TrajectoryConfig readRemote(SharedPreferences prefs) {
        try {
            return TrajectoryConfig.fromJson(prefs.getString(KEY_JSON, null));
        } catch (Throwable ignored) {
            return null;
        }
    }
}
