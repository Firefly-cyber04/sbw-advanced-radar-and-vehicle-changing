package com.f16addon;

import com.f16addon.core.SpatialGrid;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/** Built at most once per dimension and tick, never requests chunks. Server thread only. */
public final class BattleIndex {
    private static final Map<ServerLevel, Snapshot> LEVELS = new IdentityHashMap<>();
    private BattleIndex() {}

    private static Snapshot snapshot(ServerLevel level) {
        Snapshot result = LEVELS.computeIfAbsent(level, ignored -> new Snapshot());
        long now = level.getGameTime();
        if (result.built && result.tick == now) return result;
        result.built = true;
        result.tick = now;
        result.vehicles.clear(); result.projectiles.clear(); result.extras.clear();
        UnitTracker.Units units = UnitTracker.get(level);
        for (Entity e : units.vehicles()) if (UnitTracker.isLive(level, e)) result.vehicles.add(e.getX(), e.getZ(), e);
        for (Entity e : units.projectiles()) if (UnitTracker.isLive(level, e)) result.projectiles.add(e.getX(), e.getZ(), e);
        for (Entity e : units.extra()) if (UnitTracker.isLive(level, e)) result.extras.add(e.getX(), e.getZ(), e);
        return result;
    }

    public static List<Entity> vehicles(ServerLevel level, Entity center, double range) {
        return snapshot(level).vehicles.near(center.getX(), center.getZ(), range + 16.0);
    }
    public static List<Entity> projectiles(ServerLevel level, Entity center, double range) {
        return snapshot(level).projectiles.near(center.getX(), center.getZ(), range);
    }
    public static List<Entity> extras(ServerLevel level, Entity center, double range) {
        return snapshot(level).extras.near(center.getX(), center.getZ(), range + 16.0);
    }
    static void forget(ServerLevel level) { LEVELS.remove(level); }
    static void clear() { LEVELS.clear(); }

    private static final class Snapshot {
        boolean built;
        long tick;
        final SpatialGrid<Entity> vehicles = new SpatialGrid<>(128);
        final SpatialGrid<Entity> projectiles = new SpatialGrid<>(128);
        final SpatialGrid<Entity> extras = new SpatialGrid<>(128);
    }
}
