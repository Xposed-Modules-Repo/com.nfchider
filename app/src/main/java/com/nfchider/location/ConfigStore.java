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
public final class ConfigStore {

    private static final String TAG = "NfcHider/Loc";

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
     * {@link java.util.WeakHashMap} (see {@code RemotePreferences} line 34 of
     * io.github.libxposed:service), so a listener that is not referenced anywhere
     * else may be garbage-collected at any time. Without this anchor the push
     * channel silently dies after a GC and config changes made in the module app
     * never reach hooked processes until they are restarted.
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
            // retry: previously the flag was set before the try, so one transient
            // failure silently killed the push channel for the whole process.
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
     * Legacy alias for {@link #isSimActive()}, kept only so older call sites
     * keep compiling. Prefer the explicit method: "hook enabled" used to mean
     * both NFC hiding and location simulation, which is exactly what made the
     * two features impossible to control independently.
     */
    public static boolean isHookEnabled() {
        TrajectoryConfig c = get();
        return c != null && c.masterOn && c.enabled;
    }

    /**
     * True when NFC hardware must be hidden from this process.
     *
     * <p>Deliberately independent of {@link #isHookEnabled()}: the two features
     * have different defaults and different preconditions. NFC hiding is on as
     * soon as a config exists (or even before one does — see
     * {@link TrajectoryConfig#fromJson} and the builder default), so a fresh
     * install hides NFC without the user having to discover a switch.
     */
    public static boolean isNfcHidden() {
        TrajectoryConfig c = get();
        // No config yet: the module app has never run. Fail "hidden" rather than
        // "exposed" — the user installed an app called "NFC Hider".
        if (c == null) return true;
        return c.masterOn && c.nfcHide;
    }

    /** True when the simulation is enabled and has at least one waypoint. */
    public static boolean isSimActive() {
        TrajectoryConfig c = get();
        return c != null && c.masterOn && c.enabled && c.hasRoute();
    }

    /** True when a config was ever successfully loaded. */
    public static boolean haveConfig() {
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

        // 1. Push channel first — it is the cheap one and reflects live edits.
        TrajectoryConfig pushed = null;
        if (remotePrefs != null) pushed = readRemote(remotePrefs);

        // 2. Always consult the provider too. The old code returned early whenever
        //    the cache was non-empty, so a stale pushed value (module app
        //    force-stopped mid-push) masked the file the app actually wrote and
        //    switch changes never reached this process. The provider reads the
        //    module app's own file, so it wins whenever it has an answer.
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
        // else: keep the previous value; a null cache means "NFC hidden, sim off".
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
