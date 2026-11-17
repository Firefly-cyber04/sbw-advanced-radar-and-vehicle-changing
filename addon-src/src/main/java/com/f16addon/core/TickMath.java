package com.f16addon.core;

/** Tick deadlines that explicitly handle uninitialized clocks and world time rewinds. */
public final class TickMath {
    public static final long NEVER = Long.MIN_VALUE;
    private TickMath() {}

    public static boolean due(long now, long previous, long interval) {
        if (previous == NEVER || now < previous) return true;
        long elapsed = now - previous;
        return elapsed < 0 || elapsed >= Math.max(1L, interval);
    }

    public static boolean staggered(long now, int entityId, int interval) {
        return Math.floorMod(Math.floorMod(now, interval) + Math.floorMod(entityId, interval), interval) == 0;
    }
}
