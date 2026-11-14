package com.f16addon;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * "Which entity is being shot at by how many air-to-air missiles", rebuilt lazily at most every few ticks from the
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
      final Map<String, Integer> aamsByTarget = new HashMap<>();
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
            snap.aamsByTarget.merge(target, 1, Integer::sum);
         }
      }

      return snap;
   }

   /** Air-to-air / MANPADS missiles whose seeker is locked on {@code entity}. */
   public static int incomingAams(ServerLevel level, Entity entity) {
      return snapshot(level).aamsByTarget.getOrDefault(entity.getStringUUID(), 0);
   }

   static void forget(ServerLevel level) {
      SNAPSHOTS.remove(level);
   }
}
