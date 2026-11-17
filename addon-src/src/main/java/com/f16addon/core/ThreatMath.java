package com.f16addon.core;

/** Constant velocity closest-approach estimate; returns ticks, or -1 for a miss. */
public final class ThreatMath {
    private ThreatMath() {}

    public static double interceptTime(double dx, double dy, double dz,
                                       double vx, double vy, double vz,
                                       double radius, double horizon) {
        double speedSquared = vx * vx + vy * vy + vz * vz;
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        if (distanceSquared <= radius * radius) return 0.0;
        if (speedSquared < 0.0001) return -1.0;
        double t = (dx * vx + dy * vy + dz * vz) / speedSquared;
        if (!Double.isFinite(t) || t < 0.0 || t > horizon) return -1.0;
        double mx = dx - vx * t, my = dy - vy * t, mz = dz - vz * t;
        return mx * mx + my * my + mz * mz <= radius * radius ? t : -1.0;
    }

    public static int burstCooldown(int shotsInBurst, int burstSize, int shotDelay, int pause) {
        return shotsInBurst >= Math.max(1, burstSize) ? Math.max(shotDelay, pause) : shotDelay;
    }
}
