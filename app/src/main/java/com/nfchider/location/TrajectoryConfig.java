package com.nfchider.location;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable location-simulation configuration shared between the module app
 * (writer) and the hooked processes (reader).
 *
 * Serialized as JSON and transported via libxposed remote preferences or,
 * as a fallback, via the module app's {@link android.content.ContentProvider}.
 */
public class TrajectoryConfig {

    public static final String MODE_ONCE = "ONCE";
    public static final String MODE_LOOP = "LOOP";
    public static final String MODE_PINGPONG = "PINGPONG";

    public static final double DEFAULT_SPEED_MPS = 1.4;      // ~5 km/h walking
    public static final double DEFAULT_ACCURACY_M = 10.0;
    public static final long DEFAULT_UPDATE_INTERVAL_MS = 1000L;
    public static final double DEFAULT_ALTITUDE_M = 50.0;

    /** One route waypoint. */
    public static class Point {
        public final double lat;
        public final double lng;
        /** Meters, or {@link Double#NaN} when unknown. */
        public final double ele;

        public Point(double lat, double lng, double ele) {
            this.lat = lat;
            this.lng = lng;
            this.ele = ele;
        }
    }

    /** True when the simulation is active for this config. */
    public final boolean enabled;
    /** ONCE / LOOP / PINGPONG. */
    public final String mode;
    /** Movement speed in meters per second. */
    public final double speedMps;
    /** Reported horizontal accuracy in meters. */
    public final double accuracyM;
    /** Interval between delivered location updates. */
    public final long updateIntervalMs;
    /** Wall clock (System.currentTimeMillis()) when movement started. */
    public final long startAt;
    /** Ordered route waypoints; at least one for a stationary fix. */
    public final List<Point> points;

    private TrajectoryConfig(boolean enabled, String mode, double speedMps, double accuracyM,
                             long updateIntervalMs, long startAt, List<Point> points) {
        this.enabled = enabled;
        this.mode = mode;
        this.speedMps = speedMps;
        this.accuracyM = accuracyM;
        this.updateIntervalMs = updateIntervalMs;
        this.startAt = startAt;
        this.points = points;
    }

    public boolean hasRoute() {
        return points != null && !points.isEmpty();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.enabled = enabled;
        b.mode = mode;
        b.speedMps = speedMps;
        b.accuracyM = accuracyM;
        b.updateIntervalMs = updateIntervalMs;
        b.startAt = startAt;
        if (points != null) b.points.addAll(points);
        return b;
    }

    public String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("v", 1);
            o.put("enabled", enabled);
            o.put("mode", mode);
            o.put("speedMps", speedMps);
            o.put("accuracyM", accuracyM);
            o.put("updateIntervalMs", updateIntervalMs);
            o.put("startAt", startAt);
            JSONArray arr = new JSONArray();
            if (points != null) {
                for (Point p : points) {
                    JSONObject po = new JSONObject();
                    po.put("lat", p.lat);
                    po.put("lng", p.lng);
                    if (!Double.isNaN(p.ele)) po.put("ele", p.ele);
                    arr.put(po);
                }
            }
            o.put("points", arr);
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
            b.enabled = o.optBoolean("enabled", false);
            String mode = o.optString("mode", MODE_LOOP);
            b.mode = MODE_ONCE.equals(mode) || MODE_PINGPONG.equals(mode) ? mode : MODE_LOOP;
            b.speedMps = o.optDouble("speedMps", DEFAULT_SPEED_MPS);
            if (b.speedMps <= 0) b.speedMps = DEFAULT_SPEED_MPS;
            b.accuracyM = o.optDouble("accuracyM", DEFAULT_ACCURACY_M);
            if (b.accuracyM <= 0) b.accuracyM = DEFAULT_ACCURACY_M;
            b.updateIntervalMs = o.optLong("updateIntervalMs", DEFAULT_UPDATE_INTERVAL_MS);
            if (b.updateIntervalMs < 200) b.updateIntervalMs = 200;
            b.startAt = o.optLong("startAt", 0L);
            JSONArray arr = o.optJSONArray("points");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject po = arr.optJSONObject(i);
                    if (po == null) continue;
                    double lat = po.optDouble("lat", Double.NaN);
                    double lng = po.optDouble("lng", Double.NaN);
                    if (Double.isNaN(lat) || Double.isNaN(lng)) continue;
                    double ele = po.has("ele") ? po.optDouble("ele", Double.NaN) : Double.NaN;
                    b.points.add(new Point(lat, lng, ele));
                }
            }
            return b.build();
        } catch (Exception e) {
            return null;
        }
    }

    public static final class Builder {
        private boolean enabled = false;
        private String mode = MODE_LOOP;
        private double speedMps = DEFAULT_SPEED_MPS;
        private double accuracyM = DEFAULT_ACCURACY_M;
        private long updateIntervalMs = DEFAULT_UPDATE_INTERVAL_MS;
        private long startAt = 0L;
        private final List<Point> points = new ArrayList<>();

        public Builder enabled(boolean v) { this.enabled = v; return this; }
        public Builder mode(String v) { this.mode = v; return this; }
        public Builder speedMps(double v) { this.speedMps = v; return this; }
        public Builder accuracyM(double v) { this.accuracyM = v; return this; }
        public Builder updateIntervalMs(long v) { this.updateIntervalMs = v; return this; }
        public Builder startAt(long v) { this.startAt = v; return this; }
        public List<Point> points() { return points; }

        public TrajectoryConfig build() {
            return new TrajectoryConfig(enabled, mode, speedMps, accuracyM,
                    updateIntervalMs, startAt, new ArrayList<>(points));
        }
    }
}
