package com.nfchider.location;

import org.json.JSONObject;

/**
 * Immutable NFC-hiding configuration shared between the module app (writer) and
 * the hooked processes (reader).
 *
 * Serialized as JSON and transported via libxposed remote preferences or, as a
 * fallback, via the module app's {@link android.content.ContentProvider}.
 *
 * <p>Only the two switches the module actually needs are kept:
 * <ul>
 *     <li>{@code nfcHide}: hide NFC hardware from hooked apps.</li>
 *     <li>{@code masterOn}: master kill switch for every interception.</li>
 * </ul>
 * The JSON keys are kept stable so an existing config written by a previous
 * version keeps working after an upgrade.
 */
public class TrajectoryConfig {

    /** True when NFC hardware must be hidden from hooked apps. */
    public final boolean nfcHide;
    /** Master kill switch. When false, all interception is disabled. */
    public final boolean masterOn;

    private TrajectoryConfig(boolean nfcHide, boolean masterOn) {
        this.nfcHide = nfcHide;
        this.masterOn = masterOn;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.nfcHide = nfcHide;
        b.masterOn = masterOn;
        return b;
    }

    public String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("v", 1);
            o.put("nfcHide", nfcHide);
            o.put("masterOn", masterOn);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    /** Returns null when json is null/empty; throws nothing. */
    public static TrajectoryConfig fromJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            Builder b = new Builder();
            // Default true: an existing config that predates this field was almost
            // certainly written by a user who installed the module for NFC hiding.
            b.nfcHide = o.optBoolean("nfcHide", true);
            b.masterOn = o.optBoolean("masterOn", true);
            return b.build();
        } catch (Exception e) {
            return null;
        }
    }

    public static final class Builder {
        private boolean nfcHide = true;
        private boolean masterOn = true;

        public Builder nfcHide(boolean v) { this.nfcHide = v; return this; }
        public Builder masterOn(boolean v) { this.masterOn = v; return this; }

        public TrajectoryConfig build() {
            return new TrajectoryConfig(nfcHide, masterOn);
        }
    }
}
