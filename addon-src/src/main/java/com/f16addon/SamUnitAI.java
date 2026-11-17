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
import net.minecraft.world.level.Level;

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
       Entity target;
       int fireCd;
       CompoundTag data = self.getPersistentData();
       if (Sbw.isWrecked(self)) {
           data.putString("sam_target", "");
           return;
       }
       if (SupplySystem.update(level, self) || SupplySystem.wounded(self))
           return;
       if (!data.getBoolean("sam_ai_cleared")) {
           Sbw.call(self, Sbw.SET_AI_TURRET_TARGET, "");
           Sbw.call(self, Sbw.SET_AI_PASSENGER_TARGET, "");
           data.putBoolean("sam_ai_cleared", true);
       }
       if ((fireCd = data.getInt("sam_fire_cd")) > 0) {
           data.putInt("sam_fire_cd", fireCd - 1);
       }
       if ((target = SamUnitAI.resolveTarget(level, self, data)) == null) {
           return;
       }
       Vec3 aim = WorldQueries.center(target).add(target.getDeltaMovement().scale(8.0));
       Sbw.call(self, Sbw.TURRET_AIM, aim);
       Sbw.call(self, Sbw.ADJUST_TURRET, new Object[0]);
       if (data.getInt("sam_fire_cd") > 0 || SamUnitAI.saturated(target)) {
           return;
       }
       double range = SamUnitAI.rangeFor(target);
       if (self.distanceToSqr(target) > range * range || (!(target instanceof Projectile) && WorldQueries.heightAboveGround(level, target) < 8.0)) {
           return;
       }
       if (!WorldQueries.lineOfSight(level, SamUnitAI.launchPos(self), aim, self)) {
           int blind = data.getInt("sam_blind") + 1;
           data.putInt("sam_blind", blind);
           data.putInt("sam_fire_cd", 2);
           if (blind >= 20) {
               data.putString("sam_target", "");
               data.putInt("sam_blind", 0);
           }
           return;
       }
       data.putInt("sam_blind", 0);
       SamUnitAI.fire(level, self, data, target, aim);
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


   private static boolean isValidTarget(ServerLevel level, Entity self, Entity target) {
       if (!UnitTracker.isLive(level, target) || target.isSpectator()) {
           return false;
       }
       double range = SamUnitAI.rangeFor(target);
       if (self.distanceToSqr(target) > range * range) {
           return false;
       }
       if (target instanceof Projectile) {
           Projectile projectile = (Projectile) target;
           return Classify.is((Entity) projectile, 16) && !Targeting.isFriendly(self, (Entity) projectile) && !Targeting.isFriendlyOwner(self, projectile) && (Config.SAM_INTERCEPT_SMALL_SHELLS.get() || !Classify.is(projectile, Classify.SMALL_SHELL)) && (Config.SAM_ENGAGE_NON_THREATS.get() || ThreatIndex.threatTimeToGo(level, self, projectile) >= 0);
       }
       return SamUnitAI.isHostileAircraft(self, target);
   }

   private static boolean isHostileAircraft(Entity self, Entity entity) {
      Entity body = entity.getRootVehicle();
      return Sbw.isAircraft(body) && !body.onGround() && !Targeting.isFriendlyVehicle(self, body);
   }

   private static Entity scan(ServerLevel level, Entity self) {
       UnitTracker.Units units = UnitTracker.get(level);
       double maxRange = Math.max((Double) Config.SAM_RANGE.get(), (Double) Config.SAM_STEALTH_RANGE.get());
       double maxRangeSqr = maxRange * maxRange;
       boolean engageAll = (Boolean) Config.SAM_ENGAGE_NON_THREATS.get();
       ArrayList<Candidate> candidates = new ArrayList<Candidate>();
       for (Entity e : BattleIndex.projectiles(level, self, maxRange)) {
           double d;
           if (!(e instanceof Projectile))
               continue;
           Projectile projectile = (Projectile) e;
           if (!Classify.is(e, 16) || (d = self.distanceToSqr(e)) > maxRangeSqr || !UnitTracker.isLive(level, e) || Targeting.isFriendlyOwner(self, projectile))
               continue;
           if (!Config.SAM_INTERCEPT_SMALL_SHELLS.get() && Classify.is(e, Classify.SMALL_SHELL))
               continue;
           double timeToGo = ThreatIndex.threatTimeToGo(level, self, e);
           if (timeToGo >= 0.0) {
               candidates.add(new Candidate(e, Kind.THREAT, timeToGo));
               continue;
           }
           if (!engageAll)
               continue;
           candidates.add(new Candidate(e, Kind.PROJECTILE, d));
       }
       for (Targeting.Contact c2 : Targeting.contacts(level, self, maxRange)) {
           if (!c2.air() || !SamUnitAI.isHostileAircraft(self, c2.entity()))
               continue;
           candidates.add(new Candidate(c2.entity(), Kind.AIRCRAFT, c2.distSqr()));
       }
       candidates.sort(Comparator.<Candidate>comparingInt( c -> c.kind().ordinal()).thenComparingDouble(Candidate::sortKey));
       Vec3 eye = SamUnitAI.launchPos(self);
       int budget = (Integer) Config.MAX_LOS_CHECKS_PER_SCAN.get();
       Entity saturatedFallback = null;
       for (Candidate c3 : candidates) {
           Entity e = c3.entity();
           double range = SamUnitAI.rangeFor(e);
           if (self.distanceToSqr(e) > range * range || (!(e instanceof Projectile) && WorldQueries.heightAboveGround(level, e) < 8.0))
               continue;
           if (SamUnitAI.saturated(e)) {
               if (saturatedFallback != null)
                   continue;
               saturatedFallback = e;
               continue;
           }
           if (budget-- <= 0)
               break;
           if (!WorldQueries.lineOfSight(level, eye, WorldQueries.center(e), self))
               continue;
           return e;
       }
       return saturatedFallback;
   }


   private static Vec3 launchPos(Entity self) {
      Vec3 fallback = self.position().add(0.0, 1.5, 0.0).add(self.getLookAngle().scale(2.0));
      return Sbw.shootPos(self, "AAMissile", fallback);
   }

   private static void fire(ServerLevel level, Entity self, CompoundTag data, Entity target, Vec3 aim) {
       boolean intercept = target instanceof Projectile;
       if (!BattleBudget.canFire(level, intercept) || !SupplySystem.canFire(self, MISSILE_ID))
           return;
       Entity missile;
       Vec3 start = SamUnitAI.launchPos(self);
       Vec3 dir = aim.subtract(start);
       if (dir.lengthSqr() < 0.01) {
           return;
       }
       EntityType type = (EntityType) ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.tryParse((String) MISSILE_ID));
       Entity entity = missile = type == null ? null : type.create((Level) level);
       if (missile == null) {
           return;
       }
       dir = dir.normalize();
       missile.moveTo(start.x, start.y, start.z, F16FlightAI.yawOf(dir), F16FlightAI.pitchOf(dir));
       missile.setDeltaMovement(dir.scale(3.0));
       if (missile instanceof Projectile) {
           Projectile p = (Projectile) missile;
           p.setOwner(self);
       }
       Sbw.call(missile, Sbw.SET_DAMAGE, Float.valueOf(260.0f));
       Sbw.call(missile, Sbw.SET_EXPLOSION_DAMAGE, Float.valueOf(160.0f));
       Sbw.call(missile, Sbw.SET_EXPLOSION_RADIUS, Float.valueOf(6.0f));
       Sbw.lockMissile(missile, target, aim);
       BattleBudget.prepare(missile);
       missile.getPersistentData().putInt("ef_lifetime", Config.SAM_MISSILE_LIFETIME.get());
       if (level.addFreshEntity(missile)) {
           BattleBudget.fired(level);
           SupplySystem.consume(self, MISSILE_ID);
           data.putInt("sam_fire_cd", ((Integer) Config.SAM_FIRE_INTERVAL.get()).intValue());
           Targeting.registerShot(target, missile);
       }
   }
}
