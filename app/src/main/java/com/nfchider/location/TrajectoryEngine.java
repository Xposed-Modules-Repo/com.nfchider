package com.nfchider.location;

import java.util.List;

/**
 * Deterministic time-based trajectory playback: given a {@link TrajectoryConfig}
 * and the current wall clock, computes where the simulated device "is" right now.
 *
 * Both the module app (map preview) and the hooked processes (fake fixes) use
 * this class, so the two always agree on the simulated position.
 */
public final class TrajectoryEngine {

    /** Simulated position at a point in time. */
    public static class Position {
        public double lat;
        public double lng;
        /** Meters; falls back to {@link TrajectoryConfig#DEFAULT_ALTITUDE_M} when the route has none. */
        public double ele;
        /** Movement direction in degrees [0, 360). */
        public double bearingDeg;
        /** Current speed in m/s; 0 once arrived in ONCE mode. */
        public double speedMps;
        /** Distance covered along the route in meters. */
        public double distanceM;
        /** Total route length in meters. */
        public double totalM;
        /** Fraction of the route completed, [0, 1]. */
        public double progress;
        /** True in ONCE mode after the end of the route was reached. */
        public boolean arrived;
    }

    private static final double EARTH_RADIUS_M = 6371000.0;

    private TrajectoryEngine() {
    }

    public static Position positionAt(TrajectoryConfig cfg, long nowMillis) {
        Position pos = new Position();
        List<TrajectoryConfig.Point> pts = cfg.points;
        pos.speedMps = cfg.speedMps;
        pos.ele = TrajectoryConfig.DEFAULT_ALTITUDE_M;

        if (pts == null || pts.isEmpty()) {
            pos.arrived = true;
            pos.speedMps = 0;
            return pos;
        }

        TrajectoryConfig.Point first = pts.get(0);
        pos.lat = first.lat;
        pos.lng = first.lng;
        if (!Double.isNaN(first.ele)) pos.ele = first.ele;

        if (pts.size() == 1) {
            pos.arrived = true;
            pos.speedMps = 0;
            return pos;
        }

        double total = totalDistanceM(cfg);
        pos.totalM = total;
        if (total <= 0.0) {
            pos.arrived = true;
            pos.speedMps = 0;
            return pos;
        }

        long startAt = cfg.startAt > 0 ? cfg.startAt : 0;
        double elapsedSec = Math.max(0.0, (nowMillis - startAt) / 1000.0);
        double d = cfg.speedMps * elapsedSec;

        boolean arrived = false;
        String mode = cfg.mode == null ? TrajectoryConfig.MODE_LOOP : cfg.mode;
        switch (mode) {
            case TrajectoryConfig.MODE_ONCE:
                if (d >= total) {
                    d = total;
                    arrived = true;
                }
                break;
            case TrajectoryConfig.MODE_PINGPONG: {
                double cycle = total * 2.0;
                double r = d % cycle;
                d = r <= total ? r : cycle - r;
                break;
            }
            case TrajectoryConfig.MODE_LOOP:
            default:
                d = d % total;
                break;
        }

        pos.distanceM = d;
        pos.progress = Math.min(1.0, d / total);
        pos.arrived = arrived;
        if (arrived) pos.speedMps = 0;

        // Locate the segment containing arc-length d and interpolate.
        double acc = 0.0;
        for (int i = 0; i < pts.size() - 1; i++) {
            TrajectoryConfig.Point a = pts.get(i);
            TrajectoryConfig.Point b = pts.get(i + 1);
            double seg = distanceM(a.lat, a.lng, b.lat, b.lng);
            if (d <= acc + seg || i == pts.size() - 2) {
                double frac = seg > 0.0 ? Math.min(1.0, (d - acc) / seg) : 0.0;
                if (frac < 0) frac = 0;
                pos.lat = a.lat + (b.lat - a.lat) * frac;
                pos.lng = a.lng + (b.lng - a.lng) * frac;
                double eleA = Double.isNaN(a.ele) ? TrajectoryConfig.DEFAULT_ALTITUDE_M : a.ele;
                double eleB = Double.isNaN(b.ele) ? TrajectoryConfig.DEFAULT_ALTITUDE_M : b.ele;
                pos.ele = eleA + (eleB - eleA) * frac;
                pos.bearingDeg = bearingDeg(a.lat, a.lng, b.lat, b.lng);
                break;
            }
            acc += seg;
        }
        return pos;
    }

    public static double totalDistanceM(TrajectoryConfig cfg) {
        if (cfg == null || cfg.points == null || cfg.points.size() < 2) return 0.0;
        double total = 0.0;
        List<TrajectoryConfig.Point> pts = cfg.points;
        for (int i = 0; i < pts.size() - 1; i++) {
            TrajectoryConfig.Point a = pts.get(i);
            TrajectoryConfig.Point b = pts.get(i + 1);
            total += distanceM(a.lat, a.lng, b.lat, b.lng);
        }
        return total;
    }

    /** Great-circle distance in meters (haversine). */
    public static double distanceM(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double s = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(s)));
    }

    /** Initial bearing from (lat1,lng1) to (lat2,lng2) in degrees [0, 360). */
    public static double bearingDeg(double lat1, double lng1, double lat2, double lng2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dLng = Math.toRadians(lng2 - lng1);
        double y = Math.sin(dLng) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLng);
        double deg = Math.toDegrees(Math.atan2(y, x));
        return (deg + 360.0) % 360.0;
    }
}
