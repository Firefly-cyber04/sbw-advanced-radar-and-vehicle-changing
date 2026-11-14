package com.f16addon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Stationary Wasp (AKM) SAM.
 *
 * <p>Changes against 1.0.0:
 * <ul>
 *    <li>Candidates come from the tracked projectile / vehicle sets, not a 2000-block AABB query every 10 ticks.</li>
 *    <li>Line of sight is no longer raycast inside the filter for <i>every</i> candidate (up to 1000 blocks each,
 *        loading chunks on the way) — only for the best few after sorting.</li>
 *    <li>Threat evaluation: incoming missiles / shells aimed at the SAM or a friendly unit come first, sorted by
 *        time-to-impact; projectiles flying away from everything are ignored by default.</li>
 *    <li>Shoot-look-shoot: launchers share an engagement board so four SAMs do not ripple 4 missiles into one shell.</li>
 *    <li>Missile launch yaw used {@code Math.min(-x, z)} instead of {@code atan2(-x, z)}.</li>
 * </ul>
 */
public final class SamUnitAI {
   private static final double MIN_TARGET_HEIGHT = 8.0;
   private static final float IR_DAMAGE = 260.0F;
   private static final float IR_EXPLOSION_DAMAGE = 160.0F;
   private static final float IR_EXPLOSION_RADIUS = 6.0F;
   private static final double MISSILE_SPEED = 3.0;
   private static final double UNGUIDED_THREAT_RADIUS = 400.0;
   /** cos(20°): an unguided projectile is a threat if it flies within ~20° of a friendly unit. */
   private static final double THREAT_CONE = 0.94;
   private static final int BLIND_LIMIT = 20;
   private static final String MISSILE_ID = "superbwarfare:ru_9m336_missile";

   private SamUnitAI() {
   }

   private enum Kind { THREAT, AIRCRAFT, PROJECTILE }

   private record Candidate(Entity entity, Kind kind, double sortKey) {
   }

   public static void tick(ServerLevel level, Entity self) {
      CompoundTag data = self.getPersistentData();
      if (Sbw.isWrecked(self)) {
         data.putString("sam_target", "");
         return;
      }

      if (!data.getBoolean("sam_ai_cleared")) {
         // The vehicle's own weapon AI must never pick targets behind our filters.
         Sbw.call(self, Sbw.SET_AI_TURRET_TARGET, "");
         Sbw.call(self, Sbw.SET_AI_PASSENGER_TARGET, "");
         data.putBoolean("sam_ai_cleared", true);
      }

      int fireCd = data.getInt("sam_fire_cd");
      if (fireCd > 0) {
         data.putInt("sam_fire_cd", fireCd - 1);
      }

      Entity target = resolveTarget(level, self, data);
      if (target == null) {
         return;
      }

      Vec3 aim = WorldQueries.center(target).add(target.getDeltaMovement().scale(8.0));
      Sbw.call(self, Sbw.TURRET_AIM, aim);
      Sbw.call(self, Sbw.ADJUST_TURRET);
      if (data.getInt("sam_fire_cd") > 0 || saturated(target)) {
         return;
      }

      double range = rangeFor(target);
      if (self.distanceToSqr(target) > range * range || WorldQueries.heightAboveGround(level, target) < MIN_TARGET_HEIGHT) {
         return;
      }

      if (!WorldQueries.lineOfSight(level, launchPos(self), aim, self)) {
         int blind = data.getInt("sam_blind") + 1;
         data.putInt("sam_blind", blind);
         data.putInt("sam_fire_cd", 2);
         if (blind >= BLIND_LIMIT) {
            data.putString("sam_target", "");
            data.putInt("sam_blind", 0);
         }

         return;
      }

      data.putInt("sam_blind", 0);
      fire(level, self, data, target, aim);
   }

   private static double rangeFor(Entity target) {
      return Classify.isFifthGen(target) ? Config.SAM_STEALTH_RANGE.get() : Config.SAM_RANGE.get();
   }

   private static boolean saturated(Entity target) {
      int limit = target instanceof Projectile ? Config.SAM_MISSILES_PER_PROJECTILE.get() : Config.SAM_MISSILES_PER_AIRCRAFT.get();
      return Targeting.missilesInFlight(target) >= limit;
   }

   private static Entity resolveTarget(ServerLevel level, Entity self, CompoundTag data) {
      Entity current = Targeting.byUuid(level, data.getString("sam_target"));
      boolean valid = current != null && isValidTarget(level, self, current);
      boolean scheduled = (level.getGameTime() + self.getId()) % Config.SAM_SCAN_INTERVAL.get() == 0L;
      // Re-scan on schedule (a closer threat may have appeared, or the current one is saturated) or as soon as the
      // current target becomes invalid.
      if (valid && !scheduled) {
         return current;
      }

      if (current == null && !scheduled && data.getString("sam_target").isEmpty()) {
         return null;
      }

      Entity best = scan(level, self);
      data.putString("sam_target", best == null ? "" : best.getStringUUID());
      if (best != current) {
         data.putInt("sam_blind", 0);
      }

      return best;
   }

   /** Cheap per-tick validation (type / side / range). LOS and height are checked when firing. */
   private static boolean isValidTarget(ServerLevel level, Entity self, Entity target) {
      if (!UnitTracker.isLive(level, target) || target.isSpectator()) {
         return false;
      }

      double range = rangeFor(target);
      if (self.distanceToSqr(target) > range * range) {
         return false;
      }

      if (target instanceof Projectile projectile) {
         return Classify.is(projectile, Classify.AIR_DEFENSE_TARGET) && !Targeting.isFriendly(self, projectile)
            && !Targeting.isFriendlyOwner(self, projectile);
      }

      return isHostileAircraft(self, target);
   }

   private static boolean isHostileAircraft(Entity self, Entity entity) {
      Entity body = entity.getRootVehicle();
      return Sbw.isAircraft(body) && !body.onGround() && !Targeting.isFriendlyVehicle(self, body);
   }

   private static Entity scan(ServerLevel level, Entity self) {
      UnitTracker.Units units = UnitTracker.get(level);
      double maxRange = Math.max(Config.SAM_RANGE.get(), Config.SAM_STEALTH_RANGE.get());
      double maxRangeSqr = maxRange * maxRange;
      boolean engageAll = Config.SAM_ENGAGE_NON_THREATS.get();
      List<Candidate> candidates = new ArrayList<>();
      Entity[] friendlies = friendlyUnits(units);

      for (Entity e : units.projectiles()) {
         if (!(e instanceof Projectile projectile) || !Classify.is(e, Classify.AIR_DEFENSE_TARGET)) {
            continue;
         }

         double d = self.distanceToSqr(e);
         if (d > maxRangeSqr || !UnitTracker.isLive(level, e) || Targeting.isFriendlyOwner(self, projectile)) {
            continue;
         }

         double timeToGo = threatTimeToGo(level, self, e, friendlies);
         if (timeToGo >= 0.0) {
            candidates.add(new Candidate(e, Kind.THREAT, timeToGo));
         } else if (engageAll) {
            candidates.add(new Candidate(e, Kind.PROJECTILE, d));
         }
      }

      for (Targeting.Contact c : Targeting.contacts(level, self, maxRange)) {
         if (c.air() && isHostileAircraft(self, c.entity())) {
            candidates.add(new Candidate(c.entity(), Kind.AIRCRAFT, c.distSqr()));
         }
      }

      candidates.sort(Comparator.<Candidate>comparingInt(c -> c.kind().ordinal()).thenComparingDouble(Candidate::sortKey));
      Vec3 eye = launchPos(self);
      int budget = Config.MAX_LOS_CHECKS_PER_SCAN.get();
      Entity saturatedFallback = null;
      for (Candidate c : candidates) {
         Entity e = c.entity();
         double range = rangeFor(e);
         if (self.distanceToSqr(e) > range * range || WorldQueries.heightAboveGround(level, e) < MIN_TARGET_HEIGHT) {
            continue;
         }

         if (saturated(e)) {
            // Already enough missiles on it; track it only if nothing else is worth a shot.
            if (saturatedFallback == null) {
               saturatedFallback = e;
            }

            continue;
         }

         if (budget-- <= 0) {
            break;
         }

         if (WorldQueries.lineOfSight(level, eye, WorldQueries.center(e), self)) {
            return e;
         }
      }

      return saturatedFallback;
   }

   private static Entity[] friendlyUnits(UnitTracker.Units units) {
      Entity[] ground = units.ground();
      Entity[] sams = units.sams();
      Entity[] all = new Entity[ground.length + sams.length];
      System.arraycopy(ground, 0, all, 0, ground.length);
      System.arraycopy(sams, 0, all, ground.length, sams.length);
      return all;
   }

   /**
    * Ticks until impact if the projectile threatens the SAM or a friendly unit, otherwise -1.
    * Guided munitions are judged by their lock, unguided ones by heading.
    */
   private static double threatTimeToGo(ServerLevel level, Entity self, Entity projectile, Entity[] friendlies) {
      Vec3 pos = projectile.position();
      Vec3 vel = projectile.getDeltaMovement();
      double speed = Math.max(vel.length(), 0.05);
      String lock = Sbw.targetUuid(projectile);
      if (!lock.isEmpty() && !"none".equals(lock)) {
         Entity locked = Targeting.byUuid(level, lock);
         if (locked != null && (locked == self || Targeting.isFriendly(self, locked) || Targeting.isFriendlyVehicle(self, locked.getRootVehicle()))) {
            return pos.distanceTo(locked.position()) / speed;
         }

         return -1.0;
      }

      if (vel.lengthSqr() < 0.01) {
         return -1.0;
      }

      Vec3 dir = vel.scale(1.0 / speed);
      double best = -1.0;
      if (heading(pos, dir, self.position())) {
         best = pos.distanceTo(self.position()) / speed;
      }

      for (Entity friend : friendlies) {
         if (friend.level() == level && heading(pos, dir, friend.position())) {
            double t = pos.distanceTo(friend.position()) / speed;
            if (best < 0.0 || t < best) {
               best = t;
            }
         }
      }

      return best;
   }

   private static boolean heading(Vec3 pos, Vec3 dir, Vec3 at) {
      Vec3 to = at.subtract(pos);
      double d = to.length();
      return d <= UNGUIDED_THREAT_RADIUS && d > 1.0E-3 && dir.dot(to.scale(1.0 / d)) > THREAT_CONE;
   }

   private static Vec3 launchPos(Entity self) {
      Vec3 fallback = self.position().add(0.0, 1.5, 0.0).add(self.getLookAngle().scale(2.0));
      return Sbw.shootPos(self, "AAMissile", fallback);
   }

   private static void fire(ServerLevel level, Entity self, CompoundTag data, Entity target, Vec3 aim) {
      Vec3 start = launchPos(self);
      Vec3 dir = aim.subtract(start);
      if (dir.lengthSqr() < 0.01) {
         return;
      }

      EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.tryParse(MISSILE_ID));
      Entity missile = type == null ? null : type.create(level);
      if (missile == null) {
         return;
      }

      dir = dir.normalize();
      missile.moveTo(start.x, start.y, start.z, F16FlightAI.yawOf(dir), F16FlightAI.pitchOf(dir));
      missile.setDeltaMovement(dir.scale(MISSILE_SPEED));
      if (missile instanceof Projectile p) {
         p.setOwner(self);
      }

      Sbw.call(missile, Sbw.SET_DAMAGE, IR_DAMAGE);
      Sbw.call(missile, Sbw.SET_EXPLOSION_DAMAGE, IR_EXPLOSION_DAMAGE);
      Sbw.call(missile, Sbw.SET_EXPLOSION_RADIUS, IR_EXPLOSION_RADIUS);
      Sbw.lockMissile(missile, target, aim);
      if (level.addFreshEntity(missile)) {
         data.putInt("sam_fire_cd", Config.SAM_FIRE_INTERVAL.get());
         Targeting.registerShot(target, missile);
      }
   }
}
