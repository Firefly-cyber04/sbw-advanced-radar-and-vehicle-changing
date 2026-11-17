package com.f16addon;

import com.f16addon.core.ThreatMath;
import com.f16addon.core.TickMath;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Shared lock index and shared unguided-threat analysis, refreshed every five ticks. */
public final class ThreatIndex {
    private static final Map<ServerLevel, Snapshot> SNAPSHOTS = new IdentityHashMap<>();
    private ThreatIndex() {}

    private static Snapshot snapshot(ServerLevel level) {
        Snapshot s = SNAPSHOTS.computeIfAbsent(level, ignored -> new Snapshot());
        long now = level.getGameTime();
        if (!TickMath.due(now, s.builtAt, 5)) return s;
        s.builtAt = now;
        s.aamsByTarget.clear(); s.unguidedThreats.clear();
        UnitTracker.Units units = UnitTracker.get(level);
        for (Entity missile : units.aams()) {
            if (!UnitTracker.isLive(level, missile)) continue;
            String lock = Sbw.targetUuid(missile);
            if (!lock.isEmpty() && !"none".equals(lock)) s.aamsByTarget.computeIfAbsent(lock, ignored -> new ArrayList<>(2)).add(missile);
        }
        List<Entity> protectedUnits = new ArrayList<>();
        for (Entity e : units.ground()) if (UnitTracker.isLive(level, e) && !Sbw.isWrecked(e)) protectedUnits.add(e);
        for (Entity e : units.sams()) if (UnitTracker.isLive(level, e) && !Sbw.isWrecked(e)) protectedUnits.add(e);
        for (Entity e : units.f16()) if (UnitTracker.isLive(level, e) && !Sbw.isWrecked(e)) protectedUnits.add(e);
        for (Entity projectile : units.projectiles()) {
            if (!UnitTracker.isLive(level, projectile) || !Classify.is(projectile, Classify.AIR_DEFENSE_TARGET)) continue;
            if (!protectedUnits.isEmpty() && projectile instanceof net.minecraft.world.entity.projectile.Projectile p
                    && Targeting.isFriendlyOwner(protectedUnits.get(0), p)) continue;
            String lock = Sbw.targetUuid(projectile);
            if (!lock.isEmpty() && !"none".equals(lock)) continue;
            double best = -1;
            for (Entity friend : protectedUnits) {
                if (projectile.distanceToSqr(friend) > 400 * 400) continue;
                double time = predictedHit(projectile, friend);
                if (time >= 0 && (best < 0 || time < best)) best = time;
            }
            if (best >= 0) s.unguidedThreats.put(projectile, best);
        }
        return s;
    }

    private static double predictedHit(Entity projectile, Entity target) {
        Vec3 delta = WorldQueries.center(target).subtract(projectile.position());
        Vec3 relativeVelocity = projectile.getDeltaMovement().subtract(target.getDeltaMovement());
        double radius = Math.max(8, target.getBbWidth() + 4.0);
        return ThreatMath.interceptTime(delta.x, delta.y, delta.z,
                relativeVelocity.x, relativeVelocity.y, relativeVelocity.z, radius, 100);
    }

    public static double threatTimeToGo(ServerLevel level, Entity self, Entity projectile) {
        String lock = Sbw.targetUuid(projectile);
        if (!lock.isEmpty() && !"none".equals(lock)) {
            Entity locked = Targeting.byUuid(level, lock);
            if (locked != null && (locked == self || Targeting.isFriendly(self, locked)
                    || Targeting.isFriendlyVehicle(self, locked.getRootVehicle()))) {
                return projectile.position().distanceTo(locked.position()) / Math.max(0.05, projectile.getDeltaMovement().length());
            }
            return -1;
        }
        // Expensive prediction over protected units is shared by all SAMs.
        return snapshot(level).unguidedThreats.getOrDefault(projectile, -1.0);
    }

    public static int incomingAams(ServerLevel level, Entity target) {
        List<Entity> list = snapshot(level).aamsByTarget.get(target.getStringUUID());
        if (list == null) return 0;
        int count = 0;
        for (Entity missile : list) if (UnitTracker.isLive(level, missile) && target.getStringUUID().equals(Sbw.targetUuid(missile))) count++;
        return count;
    }

    public static Entity nearestClosingAam(ServerLevel level, Entity target) {
        List<Entity> list = snapshot(level).aamsByTarget.get(target.getStringUUID());
        if (list == null) return null;
        Entity best = null;
        double nearest = Double.MAX_VALUE;
        for (Entity missile : list) {
            if (!UnitTracker.isLive(level, missile) || !target.getStringUUID().equals(Sbw.targetUuid(missile))) continue;
            double distance = missile.distanceToSqr(target);
            if (distance < nearest && missile.getDeltaMovement().dot(target.position().subtract(missile.position())) > 0) {
                best = missile; nearest = distance;
            }
        }
        return best;
    }

    static void forget(ServerLevel level) { SNAPSHOTS.remove(level); }
    static void clear() { SNAPSHOTS.clear(); }
    private static final class Snapshot {
        long builtAt = TickMath.NEVER;
        final Map<String, List<Entity>> aamsByTarget = new HashMap<>();
        final Map<Entity, Double> unguidedThreats = new IdentityHashMap<>();
    }
}
