package com.f16addon;

import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

public final class F16FlightAI {
   private static final double PATROL_RADIUS = 100.0;
   private static final double TAKEOFF_ALTITUDE = 70.0;
   private static final double MIN_GROUND_CLEARANCE = 50.0;
   private static final double MIN_SPEED = 3.34;
   private static final double MAX_SPEED = 5.0;
   private static final double PATROL_SPEED = 3.4;
   private static final double COMBAT_SPEED = 4.5;
   private static final double BREAK_SPEED = 4.0;
   private static final double GUN_SPEED = 8.0;
   /** Re-target only if the new contact is clearly better (prevents flip-flopping between two similar targets). */
   private static final double SWITCH_HYSTERESIS = 0.6;
   private static final int DECOY_CHECK_INTERVAL = 10;

   private static final String CANNON = "superbwarfare:small_cannon_shell";
   private static final String AIM9 = "superbwarfare:ru_9m336_missile";
   private static final String AIM120 = "dragonrise_reforge:aim120";
   private static final String BOMB = "superbwarfare:mk_82";
   private static final String AGM = "superbwarfare:kh_39";

   private F16FlightAI() {
   }

   public static boolean isWrecked(Entity self) {
      return Sbw.isWrecked(self);
   }

   public static void tick(ServerLevel level, Entity self) {
      CompoundTag data = self.getPersistentData();
      initHome(self, data);
      if (isWrecked(self)) {
         if (!data.getBoolean("f16_wreck_stopped")) {
            data.putBoolean("f16_wreck_stopped", true);
            data.putBoolean("f16_airborne", false);
            data.putString("f16_locked_target", "");
            data.putString("f16_target", "");
            self.setDeltaMovement(Vec3.ZERO);
         }

         return;
      }

      if (self.onGround() && data.getBoolean("f16_airborne")) {
         data.putBoolean("f16_airborne", false);
         data.putDouble("f16_climb_speed", 0.0);
      }

      if (!data.getBoolean("f16_airborne")) {
         takeOff(level, self, data);
         return;
      }

      maybeReleaseDecoy(level, self, data);
      String mode = data.getString("f16_mode");
      if (mode.equals("patrol") || mode.equals("return") || isLeashed(level, self, data)) {
         data.putString("f16_target", "");
         patrol(level, self, data);
         return;
      }

      Entity target = resolveTarget(level, self, data);
      if (target != null) {
         combat(level, self, data, target);
      } else {
         patrol(level, self, data);
      }
   }

   private static void initHome(Entity self, CompoundTag data) {
      if (!data.contains("f16_home_x")) {
         data.putDouble("f16_home_x", self.getX());
         data.putDouble("f16_home_y", self.getY());
         data.putDouble("f16_home_z", self.getZ());
         data.putFloat("f16_yaw", self.getYRot());
         data.putFloat("f16_orbit_angle", 0.0F);
         data.putBoolean("f16_airborne", false);
         data.putDouble("f16_speed", 0.0);
         data.putString("f16_mode", "auto");
      }

      if (!data.contains("f16_mode")) {
         data.putString("f16_mode", "auto");
      }
   }

   private static Vec3 home(CompoundTag data) {
      return new Vec3(data.getDouble("f16_home_x"), data.getDouble("f16_home_y"), data.getDouble("f16_home_z"));
   }

   /**
    * Jets break off when they wander too far from home or are about to fly out of the simulated (entity-ticking) area.
    * In 1.0.0 they kept chasing, flew into chunks where nothing ticks, and froze in mid-air — or forced the server to
    * load terrain under them.
    */
   private static boolean isLeashed(ServerLevel level, Entity self, CompoundTag data) {
      Vec3 home = home(data);
      double leash = Config.F16_LEASH_RADIUS.get();
      if (horizontalDistSqr(self.position(), home) > leash * leash) {
         return true;
      }

      Vec3 ahead = self.position().add(self.getLookAngle().multiply(1.0, 0.0, 1.0).normalize().scale(48.0));
      return !WorldQueries.isEntityTicking(level, ahead.x, self.getY(), ahead.z);
   }

   private static double horizontalDistSqr(Vec3 a, Vec3 b) {
      double dx = a.x - b.x;
      double dz = a.z - b.z;
      return dx * dx + dz * dz;
   }

   private static int ground(ServerLevel level, Entity self, CompoundTag data, double x, double z) {
      int fallback = data.contains("f16_last_ground") ? data.getInt("f16_last_ground") : Mth.floor(data.getDouble("f16_home_y"));
      int ground = WorldQueries.height(level, Heightmap.Types.WORLD_SURFACE, x, z, fallback);
      if (ground != fallback) {
         data.putInt("f16_last_ground", ground);
      }

      return ground;
   }

   private static void takeOff(ServerLevel level, Entity self, CompoundTag data) {
      double homeY = data.getDouble("f16_home_y");
      int ground = ground(level, self, data, self.getX(), self.getZ());
      double targetY = Math.max(homeY + TAKEOFF_ALTITUDE, ground + MIN_GROUND_CLEARANCE + 5.0);
      double climb = Math.min(0.55, data.getDouble("f16_climb_speed") + 0.025);
      data.putDouble("f16_climb_speed", climb);
      if (self.getY() < targetY) {
         double y = Math.min(targetY, self.getY() + climb);
         self.setPos(self.getX(), y, self.getZ());
         self.setYRot(data.getFloat("f16_yaw"));
         self.setXRot(-18.0F);
         self.yRotO = self.getYRot();
         self.xRotO = self.getXRot();
         self.setDeltaMovement(0.0, climb, 0.0);
         self.hasImpulse = true;
      } else {
         data.putBoolean("f16_airborne", true);
         data.putDouble("f16_climb_speed", 0.0);
         data.putDouble("f16_speed", PATROL_SPEED);
         if (!data.getBoolean("f16_gear_up")) {
            Sbw.call(self, Sbw.SET_GEAR_UP, true);
            data.putBoolean("f16_gear_up", true);
         }
      }
   }

   private static void patrol(ServerLevel level, Entity self, CompoundTag data) {
      Vec3 home = home(data);
      float angle = data.getFloat("f16_orbit_angle") + 0.034F;
      if (angle > (float) (Math.PI * 2)) {
         angle -= (float) (Math.PI * 2);
      }

      data.putFloat("f16_orbit_angle", angle);
      double orbitX = home.x + Math.cos(angle) * PATROL_RADIUS;
      double orbitZ = home.z + Math.sin(angle) * PATROL_RADIUS;
      int ground = ground(level, self, data, orbitX, orbitZ);
      double targetY = Math.max(ground + MIN_GROUND_CLEARANCE + 5.0, home.y + 50.0);
      Vec3 radial = new Vec3(orbitX, targetY, orbitZ).subtract(self.position());
      Vec3 tangent = new Vec3(-Math.sin(angle), 0.0, Math.cos(angle));
      Vec3 desired;
      if (radial.lengthSqr() > 1.0) {
         Vec3 radialDir = radial.normalize();
         desired = radial.length() > 170.0 ? radialDir : tangent.scale(0.78).add(radialDir.scale(0.42));
      } else {
         desired = tangent;
      }

      desired = desired.add(0.0, altitudeCorrection(self.getY(), targetY, 0.06), 0.0);
      steer(level, self, data, desired, PATROL_SPEED, 2.2F, 1.4F);
   }

   private static void combat(ServerLevel level, Entity self, CompoundTag data, Entity target) {
      double distance = self.position().distanceTo(target.position());
      boolean air = Targeting.isAirborne(target) || isAboveUs(self, target);
      Vec3 targetPoint = WorldQueries.center(target);
      if (air) {
         Vec3 velocity = target.getDeltaMovement();
         if (velocity.lengthSqr() > 0.01) {
            targetPoint = targetPoint.add(velocity.scale(10.0));
         }
      } else if (self.getY() < target.getY() + 42.0) {
         targetPoint = targetPoint.add(0.0, 42.0, 0.0);
      }

      int breakTicks = data.getInt("f16_break_ticks");
      if (distance < 34.0 && breakTicks <= 0) {
         breakTicks = 32;
         data.putInt("f16_break_ticks", breakTicks);
         Vec3 toTarget = target.position().subtract(self.position()).normalize();
         double side = self.getLookAngle().cross(toTarget).y >= 0.0 ? 1.0 : -1.0;
         data.putFloat("f16_break_side", (float) side);
      }

      if (breakTicks > 0) {
         data.putInt("f16_break_ticks", breakTicks - 1);
         Vec3 away = self.position().subtract(target.position());
         if (away.lengthSqr() < 0.01) {
            away = self.getLookAngle();
         }

         away = away.normalize();
         Vec3 side = new Vec3(-away.z, 0.0, away.x).scale(data.getFloat("f16_break_side"));
         steer(level, self, data, away.add(side.scale(0.75)).add(0.0, 0.18, 0.0), BREAK_SPEED, 3.4F, 2.0F);
      } else {
         Vec3 desired = targetPoint.subtract(self.position());
         if (desired.lengthSqr() < 0.01) {
            desired = self.getLookAngle();
         }

         desired = desired.normalize();
         if (!air) {
            desired = desired.add(0.0, altitudeCorrection(self.getY(), targetPoint.y, 0.05), 0.0);
         }

         steer(level, self, data, desired, COMBAT_SPEED, 2.6F, 1.7F);
      }

      fire(level, self, data, target, distance, air);
   }

   /** 1.0.0 treated anything 10 blocks above the jet as "air" — kept as a fallback for non-SBW flyers. */
   private static boolean isAboveUs(Entity self, Entity target) {
      return !target.onGround() && target.getY() > self.getY() + 10.0;
   }

   private static double altitudeCorrection(double currentY, double targetY, double gain) {
      return Mth.clamp((targetY - currentY) * gain, -0.28, 0.28);
   }

   private static void steer(ServerLevel level, Entity self, CompoundTag data, Vec3 desiredDir, double targetSpeed, float maxYawStep, float maxPitchStep) {
      if (desiredDir.lengthSqr() < 1.0E-4) {
         return;
      }

      Vec3 desired = desiredDir.normalize();
      double speed = data.getDouble("f16_speed");
      double lookAhead = Math.max(24.0, speed * 10.0);
      Vec3 ahead = self.position().add(desired.scale(lookAhead));
      int aheadGround = ground(level, self, data, ahead.x, ahead.z);
      double safeY = aheadGround + MIN_GROUND_CLEARANCE + 10.0;
      if (self.getY() < safeY) {
         double climb = Mth.clamp((safeY - self.getY()) * 0.06, 0.0, 0.55);
         desired = desired.add(0.0, climb, 0.0).normalize();
      }

      float desiredYaw = yawOf(desired);
      float desiredPitch = Mth.clamp(pitchOf(desired), -55.0F, 55.0F);
      float oldYaw = self.getYRot();
      float oldPitch = self.getXRot();
      float yaw = smoothAngle(oldYaw, desiredYaw, maxYawStep);
      float pitch = Mth.clamp(smoothAngle(oldPitch, desiredPitch, maxPitchStep), -55.0F, 55.0F);
      self.setYRot(yaw);
      self.setXRot(pitch);
      self.yRotO = oldYaw;
      self.xRotO = oldPitch;
      applyBank(self, oldYaw, yaw);
      targetSpeed = Mth.clamp(targetSpeed, MIN_SPEED, MAX_SPEED);
      speed += Mth.clamp(targetSpeed - speed, -0.07, 0.07);
      data.putDouble("f16_speed", speed);
      Vec3 next = self.position().add(forwardOf(yaw, pitch).scale(speed));
      if (!WorldQueries.isLoaded(level, next.x, next.z)) {
         // Never move into an unloaded chunk: turn and wait for the leash logic to bring us home.
         self.setDeltaMovement(Vec3.ZERO);
         return;
      }

      double floor = ground(level, self, data, next.x, next.z) + MIN_GROUND_CLEARANCE;
      if (next.y < floor) {
         double emergencyClimb = Mth.clamp(floor - self.getY(), 0.0, 0.9);
         double y = self.getY() + emergencyClimb;
         next = new Vec3(next.x, Math.max(y, floor), next.z);
      }

      self.setPos(next.x, next.y, next.z);
      self.setDeltaMovement(Vec3.ZERO);
      self.hasImpulse = true;
   }

   private static void applyBank(Entity self, float oldYaw, float newYaw) {
      if (!(Sbw.call(self, Sbw.GET_ROLL) instanceof Number rollValue)) {
         return;
      }

      float roll = rollValue.floatValue();
      float yawDelta = Mth.wrapDegrees(newYaw - oldYaw);
      float targetRoll = Mth.clamp(-yawDelta * 8.0F, -32.0F, 32.0F);
      float newRoll = roll + Mth.clamp(targetRoll - roll, -4.0F, 4.0F);
      Sbw.call(self, Sbw.SET_PREV_ROLL, roll);
      Sbw.call(self, Sbw.SET_ROLL, newRoll);
   }

   private static float smoothAngle(float current, float target, float maxStep) {
      return current + Mth.clamp(Mth.wrapDegrees(target - current), -maxStep, maxStep);
   }

   static float yawOf(Vec3 dir) {
      return (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
   }

   static float pitchOf(Vec3 dir) {
      return (float) -Math.toDegrees(Math.asin(Mth.clamp(dir.y, -1.0, 1.0)));
   }

   private static Vec3 forwardOf(float yawDeg, float pitchDeg) {
      double yaw = Math.toRadians(yawDeg);
      double pitch = Math.toRadians(pitchDeg);
      double cosPitch = Math.cos(pitch);
      return new Vec3(-Math.sin(yaw) * cosPitch, -Math.sin(pitch), Math.cos(yaw) * cosPitch);
   }

   // ----------------------------------------------------------------------------------------------------------------
   // Targeting
   // ----------------------------------------------------------------------------------------------------------------

   private static double rangeFor(Entity target) {
      return Classify.isFifthGen(target) ? Config.F16_STEALTH_RANGE.get() : Config.F16_SCAN_RANGE.get();
   }

   private static boolean inRange(Entity self, Entity target) {
      double range = rangeFor(target);
      return self.distanceToSqr(target) <= range * range;
   }

   /** Lower is more important: players first (1.0.0 design), then aircraft, then ground. */
   private static int tier(Targeting.Contact c) {
      return c.playerManned() || c.entity() instanceof Player ? 0 : (c.air() ? 1 : 2);
   }

   private static int tierOf(Entity e) {
      boolean manned = e instanceof Player || Targeting.hasPlayer(e);
      return manned ? 0 : (Targeting.isAirborne(e) ? 1 : 2);
   }

   private static Entity resolveTarget(ServerLevel level, Entity self, CompoundTag data) {
      String lockedId = data.getString("f16_locked_target");
      if (!lockedId.isEmpty()) {
         Entity locked = Targeting.byUuid(level, lockedId);
         // Manual locks may pick anything alive (e.g. a mob); only liveness / friendliness / range are checked.
         if (UnitTracker.isLive(level, locked) && Targeting.isHostile(self, locked) && inRange(self, locked)) {
            return locked;
         }

         data.putString("f16_locked_target", "");
      }

      String targetId = data.getString("f16_target");
      Entity current = Targeting.byUuid(level, targetId);
      if (current != null && (!Targeting.stillHostile(level, self, current) || !inRange(self, current))) {
         current = null;
      }

      boolean lost = false;
      if (current == null && !targetId.isEmpty()) {
         data.putString("f16_target", "");
         lost = true;
      }

      int interval = Config.F16_SCAN_INTERVAL.get();
      int lockout = data.getInt("f16_scan_lockout");
      if (lockout > 0) {
         data.putInt("f16_scan_lockout", lockout - 1);
         return current;
      }

      // Staggered by entity id so a squadron spawned in the same tick does not scan in the same tick;
      // losing the current target triggers an immediate re-scan instead of flying blind for up to 2 s.
      boolean scheduled = (level.getGameTime() + self.getId()) % interval == 0L;
      if (!scheduled && !lost) {
         return current;
      }

      Entity best = findBestTarget(level, self, current);
      if (best == null || best == current) {
         return current;
      }

      // Radar clutter: a scan may fail to pick up a NEW contact. A target already being fought is never dropped.
      if (level.random.nextFloat() < Config.F16_DETECTION_FAILURE.get()) {
         if (current == null) {
            data.putInt("f16_scan_lockout", interval);
         }

         return current;
      }

      data.putInt("f16_react_cd", Config.F16_REACTION_TICKS.get());
      data.putString("f16_target", best.getStringUUID());
      return best;
   }

   private static Entity findBestTarget(ServerLevel level, Entity self, Entity current) {
      List<Targeting.Contact> contacts = Targeting.contacts(level, self, Config.F16_SCAN_RANGE.get());
      Targeting.Contact best = null;
      for (Targeting.Contact c : contacts) {
         if (!inRange(self, c.entity())) {
            continue;
         }

         if (best == null || tier(c) < tier(best) || (tier(c) == tier(best) && c.distSqr() < best.distSqr())) {
            best = c;
         }
      }

      if (best == null || current == null || best.entity() == current) {
         return best == null ? current : best.entity();
      }

      int currentTier = tierOf(current);
      if (tier(best) < currentTier) {
         return best.entity();
      }

      if (tier(best) == currentTier && best.distSqr() < self.distanceToSqr(current) * SWITCH_HYSTERESIS) {
         return best.entity();
      }

      return current;
   }

   // ----------------------------------------------------------------------------------------------------------------
   // Weapons
   // ----------------------------------------------------------------------------------------------------------------

   private static void fire(ServerLevel level, Entity self, CompoundTag data, Entity target, double distance, boolean air) {
      tickCooldown(data, "f16_cd_cannon");
      tickCooldown(data, "f16_cd_aim9");
      tickCooldown(data, "f16_cd_aim120");
      tickCooldown(data, "f16_cd_agm");
      tickCooldown(data, "f16_cd_bomb");
      int reaction = data.getInt("f16_react_cd");
      if (reaction > 0) {
         data.putInt("f16_react_cd", reaction - 1);
         return;
      }

      Vec3 toTarget = target.position().subtract(self.position());
      double facing = toTarget.lengthSqr() < 0.01 ? 1.0 : self.getLookAngle().normalize().dot(toTarget.normalize());
      boolean roughlyForward = facing > 0.35;
      boolean gunAligned = facing > 0.82;
      int maxMissiles = Config.F16_MAX_MISSILES_PER_TARGET.get();
      boolean missileSlot = Targeting.missilesInFlight(target) < maxMissiles;

      String weapon = null;
      String cooldownKey = null;
      int cooldown = 0;
      double speed = 0.0;
      boolean guided = false;
      boolean bomb = false;
      if (air) {
         if (distance <= 95.0 && gunAligned && data.getInt("f16_cd_cannon") <= 0) {
            weapon = CANNON; cooldownKey = "f16_cd_cannon"; cooldown = 4; speed = GUN_SPEED;
         } else if (missileSlot && distance <= 160.0 && roughlyForward && data.getInt("f16_cd_aim9") <= 0) {
            weapon = AIM9; cooldownKey = "f16_cd_aim9"; cooldown = 60; speed = 0.75; guided = true;
         } else if (missileSlot && distance <= 500.0 && roughlyForward && data.getInt("f16_cd_aim120") <= 0) {
            weapon = AIM120; cooldownKey = "f16_cd_aim120"; cooldown = 100; speed = 1.0; guided = true;
         }
      } else if (self.getY() > target.getY() + 30.0 && distance < 75.0 && data.getInt("f16_cd_bomb") <= 0) {
         weapon = BOMB; cooldownKey = "f16_cd_bomb"; cooldown = 100; speed = 0.6; bomb = true;
      } else if (missileSlot && distance <= 220.0 && roughlyForward && data.getInt("f16_cd_agm") <= 0) {
         weapon = AGM; cooldownKey = "f16_cd_agm"; cooldown = 80; speed = 1.8; guided = true;
      } else if (distance <= 125.0 && gunAligned && data.getInt("f16_cd_cannon") <= 0) {
         weapon = CANNON; cooldownKey = "f16_cd_cannon"; cooldown = 4; speed = GUN_SPEED;
      }

      if (weapon == null) {
         return;
      }

      Vec3 aim = guided ? WorldQueries.center(target) : leadPoint(self, target, speed);
      Vec3 muzzle = muzzle(self);
      // Bombs are dropped, not aimed through terrain; everything else needs a clear line (one raycast per shot).
      if (data.getInt("f16_los_cd") > 0) {
         tickCooldown(data, "f16_los_cd");
         return;
      }

      if (!bomb && !WorldQueries.lineOfSight(level, muzzle, WorldQueries.center(target), self)) {
         data.putInt("f16_los_cd", 5);
         return;
      }

      Entity projectile = fireProjectile(level, self, weapon, muzzle, aim, speed, bomb, guided ? target : null);
      if (projectile != null) {
         data.putInt(cooldownKey, cooldown);
         if (guided) {
            Targeting.registerShot(target, projectile);
         }
      }
   }

   private static Vec3 muzzle(Entity self) {
      return self.position().add(0.0, 1.4, 0.0).add(self.getLookAngle().scale(2.5));
   }

   /** First-order intercept: aim where the target will be after the projectile's flight time (1.0.0 used a fixed 8 ticks). */
   private static Vec3 leadPoint(Entity self, Entity target, double projectileSpeed) {
      Vec3 point = WorldQueries.center(target);
      Vec3 velocity = target.getDeltaMovement();
      if (velocity.lengthSqr() <= 0.01 || projectileSpeed <= 0.0) {
         return point;
      }

      double flightTicks = Mth.clamp(point.distanceTo(muzzle(self)) / projectileSpeed, 0.0, 40.0);
      return point.add(velocity.scale(flightTicks));
   }

   private static EntityType<?> type(String id) {
      ResourceLocation rl = ResourceLocation.tryParse(id);
      return rl == null ? null : ForgeRegistries.ENTITY_TYPES.getValue(rl);
   }

   private static Entity fireProjectile(ServerLevel level, Entity self, String entityId, Vec3 start, Vec3 target, double speed, boolean bomb, Entity lockTarget) {
      EntityType<?> type = type(entityId);
      if (type == null) {
         return null;
      }

      Vec3 dir = target.subtract(start);
      if (dir.lengthSqr() < 0.01) {
         return null;
      }

      Entity projectile = type.create(level);
      if (projectile == null) {
         return null;
      }

      dir = dir.normalize();
      projectile.moveTo(start.x, start.y, start.z, self.getYRot(), self.getXRot());
      if (projectile instanceof Projectile p) {
         p.setOwner(self);
      }

      projectile.setDeltaMovement(bomb ? dir.scale(speed).add(0.0, -0.12, 0.0) : dir.scale(speed));
      if (lockTarget != null) {
         Sbw.lockMissile(projectile, lockTarget, WorldQueries.center(lockTarget));
         if (entityId.equals(AIM120) || entityId.equals(AIM9)) {
            projectile.addTag("f16_aam");
         }
      }

      return level.addFreshEntity(projectile) ? projectile : null;
   }

   // ----------------------------------------------------------------------------------------------------------------
   // Countermeasures
   // ----------------------------------------------------------------------------------------------------------------

   private static void maybeReleaseDecoy(ServerLevel level, Entity self, CompoundTag data) {
      int cooldown = data.getInt("f16_decoy_cd");
      if (cooldown > 0) {
         data.putInt("f16_decoy_cd", cooldown - 1);
         return;
      }

      if ((level.getGameTime() + self.getId()) % DECOY_CHECK_INTERVAL != 0L || level.random.nextFloat() > 0.5F) {
         return;
      }

      if (ThreatIndex.incomingAams(level, self) > 0 && releaseDecoy(self)) {
         data.putInt("f16_decoy_cd", 40);
      }
   }

   private static boolean releaseDecoy(Entity self) {
      if (Sbw.call(self, Sbw.GET_DECOY_COUNT) instanceof Number n && n.intValue() <= 0) {
         return false;
      }

      if (!Sbw.has(self, Sbw.RELEASE_DECOY)) {
         return false;
      }

      Sbw.call(self, Sbw.RELEASE_DECOY);
      return true;
   }

   private static void tickCooldown(CompoundTag data, String key) {
      int value = data.getInt(key);
      if (value > 0) {
         data.putInt(key, value - 1);
      }
   }
}
