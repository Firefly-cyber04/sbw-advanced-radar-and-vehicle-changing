package com.f16addon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * "Which entity is being shot at by which air-to-air missiles", rebuilt lazily at most every few ticks from the
 * tracked projectile set. 1.0.0 had every jet run its own 96-block projectile AABB query plus a reflective
 * {@code getMethod("getTargetUUID")} per projectile for this.
 */
public final class ThreatIndex {
   private static final int REBUILD_INTERVAL = 5;
   private static final Map<ServerLevel, Snapshot> SNAPSHOTS = new IdentityHashMap<>();

   private ThreatIndex() {
   }

   private static final class Snapshot {
      long builtAt = Long.MIN_VALUE;
      final Map<String, List<Entity>> aamsByTarget = new HashMap<>();
   }

   private static Snapshot snapshot(ServerLevel level) {
      Snapshot snap = SNAPSHOTS.computeIfAbsent(level, l -> new Snapshot());
      long now = level.getGameTime();
      if (now - snap.builtAt < REBUILD_INTERVAL && now >= snap.builtAt) {
         return snap;
      }

      snap.builtAt = now;
      snap.aamsByTarget.clear();
      for (Entity projectile : UnitTracker.get(level).projectiles()) {
         if (projectile.isRemoved() || !Classify.is(projectile, Classify.AAM)) {
            continue;
         }

         String target = Sbw.targetUuid(projectile);
         if (!target.isEmpty() && !"none".equals(target)) {
            snap.aamsByTarget.computeIfAbsent(target, k -> new ArrayList<>(2)).add(projectile);
         }
      }

      return snap;
   }

   /** Air-to-air / MANPADS missiles whose seeker is locked on {@code entity}. */
   public static int incomingAams(ServerLevel level, Entity entity) {
      List<Entity> list = snapshot(level).aamsByTarget.get(entity.getStringUUID());
      if (list == null) {
         return 0;
      }

      int n = 0;
      for (Entity e : list) {
         if (!e.isRemoved()) {
            n++;
         }
      }

      return n;
   }

   /** Closest live AAM locked on {@code entity} that is still closing on it, or null. */
   public static Entity nearestClosingAam(ServerLevel level, Entity entity) {
      List<Entity> list = snapshot(level).aamsByTarget.get(entity.getStringUUID());
      if (list == null) {
         return null;
      }

      Entity best = null;
      double bestDist = Double.MAX_VALUE;
      for (Entity m : list) {
         if (m.isRemoved() || !m.isAlive()) {
            continue;
         }

         double d = m.distanceToSqr(entity);
         // Closing = the missile's velocity points towards us (a missile that overshot is no longer a threat).
         double closing = m.getDeltaMovement().dot(entity.position().subtract(m.position()));
         if (closing > 0.0 && d < bestDist) {
            bestDist = d;
            best = m;
         }
      }

      return best;
   }

   static void forget(ServerLevel level) {
      SNAPSHOTS.remove(level);
   }
}
