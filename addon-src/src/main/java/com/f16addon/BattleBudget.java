package com.f16addon;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/** Limits only this addon's newly spawned ammunition; never removes a player's projectiles. */
public final class BattleBudget {
    public static final String AI_SHOT = "enemyforces_ai_projectile";
    private static final Map<ServerLevel, Window> WINDOWS = new IdentityHashMap<>();
    private BattleBudget() {}

    private static Window window(ServerLevel level) {
        Window w = WINDOWS.computeIfAbsent(level, ignored -> new Window());
        if (w.tick != level.getGameTime()) { w.tick = level.getGameTime(); w.spent = 0; }
        return w;
    }

    public static boolean canFire(ServerLevel level, boolean intercept) {
        int reserve = intercept ? Config.INTERCEPT_RESERVED_SHOTS.get() : 0;
        return window(level).spent < Config.AI_SHOTS_PER_TICK.get() + reserve
                && UnitTracker.get(level).aiProjectileCount() < Config.MAX_AI_PROJECTILES.get() + reserve;
    }

    public static void prepare(Entity projectile) { projectile.addTag(AI_SHOT); }
    public static void fired(ServerLevel level) { window(level).spent++; }
    static void forget(ServerLevel level) { WINDOWS.remove(level); }
    static void clear() { WINDOWS.clear(); }

    private static final class Window { long tick = Long.MIN_VALUE; int spent; }
}
