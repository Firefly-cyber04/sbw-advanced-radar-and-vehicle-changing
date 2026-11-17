package com.f16addon.core;

public final class ExplosionLod {
    private ExplosionLod() {}
    /** SBW particle enum order: MINI, SMALL, MEDIUM, LARGE, HUGE, GIANT, EPIC. */
    public static int choose(int original, double distanceSquared, int detailedAlreadyUsed,
                             int maximumDetailed, double near, double middle) {
        double far = Math.max(near, middle);
        if (distanceSquared > far * far) return 0;
        if (distanceSquared > near * near || detailedAlreadyUsed >= maximumDetailed) return Math.min(original, 1);
        return original;
    }
}
