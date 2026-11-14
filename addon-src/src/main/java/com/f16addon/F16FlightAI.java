package com.f16addon;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

public final class F16FlightAI {
   private static final String TAG = "enemy_f16";
   private static final double SCAN_RANGE = 1024.0;
   private static final double FIFTH_GEN_RANGE = 512.0;
   private static final double PATROL_RADIUS = 100.0;
   private static final double TAKEOFF_ALTITUDE = 70.0;
   private static final double MIN_GROUND_CLEARANCE = 50.0;
   private static final double MIN_SPEED = 3.34;
   private static final double MAX_SPEED = 5.0;
   private static final double PATROL_SPEED = 3.4;
   private static final double COMBAT_SPEED = 4.5;
   private static final double BREAK_SPEED = 4.0;
   private static final int SCAN_INTERVAL = 40;
   private static final int REACTION_TICKS = 15;
   private static final float LOSE_TARGET_CHANCE = 0.3F;
   private static final double RADAR_MISSILE_SPEED = 1.0;
   private static final double IR_MISSILE_SPEED = 0.75;

   private F16FlightAI() {
   }

   public static void tick(ServerLevel level, Entity self) {
      CompoundTag data = self.getPersistentData();
      initHome(self, data);
      if (isWrecked(self)) {
         if (!data.getBoolean("f16_wreck_stopped")) {
            data.putBoolean("f16_wreck_stopped", true);
            data.putBoolean("f16_airborne", false);
            data.putString("f16_locked_target", "");
            self.setDeltaMovement(Vec3.ZERO);
         }
      } else {
         if (self.onGround() && data.getBoolean("f16_airborne")) {
            data.putBoolean("f16_airborne", false);
            data.putDouble("f16_climb_speed", 0.0);
         }

         if (!data.getBoolean("f16_airborne")) {
            takeOff(self, data);
         } else {
            maybeReleaseDecoy(level, self, data);
            String mode = data.getString("f16_mode");
            if (!mode.equals("patrol") && !mode.equals("return")) {
               Entity target = resolveTarget(level, self, data);
               if (target != null) {
                  combat(level, self, data, target);
               } else {
                  patrol(level, self, data);
               }
            } else {
               data.putString("f16_target", "");
               patrol(level, self, data);
            }
         }
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
   }

   private static void takeOff(Entity self, CompoundTag data) {
      double homeY = data.getDouble("f16_home_y");
      int ground = self.level().getHeight(Types.WORLD_SURFACE, Mth.floor(self.getX()), Mth.floor(self.getZ()));
      double targetY = Math.max(homeY + 70.0, (double)ground + 50.0 + 5.0);
      double climb = data.getDouble("f16_climb_speed");
      climb = Math.min(0.55, climb + 0.025);
      data.putDouble("f16_climb_speed", climb);
      if (self.getY() < targetY) {
         double y = Math.min(targetY, self.getY() + climb);
         self.setPos(self.getX(), y, self.getZ());
         self.setYRot(data.getFloat("f16_yaw"));
         self.setXRot(-18.0F);
         self.yRotO = self.getYRot();
         self.xRotO = self.getXRot();
         self.setDeltaMovement(0.0, climb, 0.0);
         self.hurtMarked = true;
      } else {
         data.putBoolean("f16_airborne", true);
         data.putDouble("f16_climb_speed", 0.0);
         data.putDouble("f16_speed", 3.4);
         if (!data.getBoolean("f16_gear_up")) {
            setGearUp(self, true);
            data.putBoolean("f16_gear_up", true);
         }
      }
   }

   private static void patrol(ServerLevel level, Entity self, CompoundTag data) {
      double homeX = data.getDouble("f16_home_x");
      double homeY = data.getDouble("f16_home_y");
      double homeZ = data.getDouble("f16_home_z");
      float angle = data.getFloat("f16_orbit_angle") + 0.034F;
      if (angle > (float) (Math.PI * 2)) {
         angle -= (float) (Math.PI * 2);
      }

      data.putFloat("f16_orbit_angle", angle);
      double orbitX = homeX + Math.cos((double)angle) * 100.0;
      double orbitZ = homeZ + Math.sin((double)angle) * 100.0;
      int ground = level.getHeight(Types.WORLD_SURFACE, Mth.floor(orbitX), Mth.floor(orbitZ));
      double targetY = Math.max((double)ground + 50.0 + 5.0, homeY + 50.0);
      Vec3 orbitPoint = new Vec3(orbitX, targetY, orbitZ);
      Vec3 radial = orbitPoint.subtract(self.position());
      Vec3 tangent = new Vec3(-Math.sin((double)angle), 0.0, Math.cos((double)angle));
      Vec3 desired;
      if (radial.lengthSqr() > 1.0) {
         Vec3 radialDir = radial.normalize();
         if (radial.length() > 170.0) {
            desired = radialDir;
         } else {
            desired = tangent.scale(0.78).add(radialDir.scale(0.42));
         }
      } else {
         desired = tangent;
      }

      desired = desired.add(0.0, altitudeCorrection(self.getY(), targetY, 0.06), 0.0);
      steer(self, data, desired, 3.4, 2.2F, 1.4F);
   }

   private static void combat(ServerLevel level, Entity self, CompoundTag data, Entity target) {
      double distance = self.position().distanceTo(target.position());
      boolean air = isAirTarget(self, target);
      Vec3 targetPoint = target.position().add(0.0, (double)target.getBbHeight() * 0.5, 0.0);
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
         data.putFloat("f16_break_side", (float)side);
      }

      if (breakTicks > 0) {
         data.putInt("f16_break_ticks", breakTicks - 1);
         Vec3 away = self.position().subtract(target.position());
         if (away.lengthSqr() < 0.01) {
            away = self.getLookAngle();
         }

         away = away.normalize();
         Vec3 side = new Vec3(-away.z, 0.0, away.x).scale((double)data.getFloat("f16_break_side"));
         Vec3 desired = away.scale(1.0).add(side.scale(0.75)).add(0.0, 0.18, 0.0);
         steer(self, data, desired, 4.0, 3.4F, 2.0F);
      } else {
         Vec3 desired = targetPoint.subtract(self.position());
         if (desired.lengthSqr() < 0.01) {
            desired = self.getLookAngle();
         }

         desired = desired.normalize();
         if (!air) {
            desired = desired.add(0.0, altitudeCorrection(self.getY(), targetPoint.y, 0.05), 0.0);
         }

         steer(self, data, desired, 4.5, 2.6F, 1.7F);
      }

      fire(level, self, data, target, distance, air);
   }

   private static double altitudeCorrection(double currentY, double targetY, double gain) {
      return Mth.clamp((targetY - currentY) * gain, -0.28, 0.28);
   }

   private static void steer(Entity self, CompoundTag data, Vec3 desiredDir, double targetSpeed, float maxYawStep, float maxPitchStep) {
      if (!(desiredDir.lengthSqr() < 1.0E-4)) {
         Vec3 desired = desiredDir.normalize();
         double speed = data.getDouble("f16_speed");
         double lookAhead = Math.max(24.0, speed * 10.0);
         Vec3 ahead = self.position().add(desired.scale(lookAhead));
         int aheadGround = self.level().getHeight(Types.WORLD_SURFACE, Mth.floor(ahead.x), Mth.floor(ahead.z));
         double safeY = (double)aheadGround + 50.0 + 10.0;
         if (self.getY() < safeY) {
            double climb = Mth.clamp((safeY - self.getY()) * 0.06, 0.0, 0.55);
            desired = desired.add(0.0, climb, 0.0).normalize();
         }

         float desiredYaw = yawOf(desired);
         float desiredPitch = Mth.clamp(pitchOf(desired), -55.0F, 55.0F);
         float oldYaw = self.getYRot();
         float oldPitch = self.getXRot();
         float yaw = smoothAngle(oldYaw, desiredYaw, maxYawStep);
         float pitch = smoothAngle(oldPitch, desiredPitch, maxPitchStep);
         pitch = Mth.clamp(pitch, -55.0F, 55.0F);
         self.setYRot(yaw);
         self.setXRot(pitch);
         self.yRotO = oldYaw;
         self.xRotO = oldPitch;
         applyBank(self, oldYaw, yaw);
         targetSpeed = Mth.clamp(targetSpeed, 3.34, 5.0);
         speed += Mth.clamp(targetSpeed - speed, -0.07, 0.07);
         data.putDouble("f16_speed", speed);
         Vec3 forward = forwardOf(yaw, pitch);
         Vec3 next = self.position().add(forward.scale(speed));
         int ground = self.level().getHeight(Types.WORLD_SURFACE, Mth.floor(next.x), Mth.floor(next.z));
         double floor = (double)ground + 50.0;
         if (next.y < floor) {
            double emergencyClimb = Mth.clamp(floor - self.getY(), 0.0, 0.9);
            Vec3 climbed = new Vec3(next.x, self.getY() + emergencyClimb, next.z);
            next = climbed.y < floor ? new Vec3(next.x, floor, next.z) : climbed;
         }

         self.setPos(next.x, next.y, next.z);
         self.setDeltaMovement(Vec3.ZERO);
         self.hurtMarked = true;
      }
   }

   private static void applyBank(Entity self, float oldYaw, float newYaw) {
      try {
         Method getRoll = self.getClass().getMethod("getRoll");
         Method setPrevRoll = self.getClass().getMethod("setPrevRoll", float.class);
         Method setRoll = self.getClass().getMethod("setRoll", float.class);
         float roll = ((Number)getRoll.invoke(self)).floatValue();
         float yawDelta = Mth.wrapDegrees(newYaw - oldYaw);
         float targetRoll = Mth.clamp(-yawDelta * 8.0F, -32.0F, 32.0F);
         float newRoll = roll + Mth.clamp(targetRoll - roll, -4.0F, 4.0F);
         setPrevRoll.invoke(self, roll);
         setRoll.invoke(self, newRoll);
      } catch (RuntimeException | ReflectiveOperationException var10) {
      }
   }

   private static float smoothAngle(float current, float target, float maxStep) {
      float delta = Mth.wrapDegrees(target - current);
      return current + Mth.clamp(delta, -maxStep, maxStep);
   }

   private static float yawOf(Vec3 dir) {
      return (float)Math.toDegrees(Math.atan2(-dir.x, dir.z));
   }

   private static float pitchOf(Vec3 dir) {
      return (float)(-Math.toDegrees(Math.asin(Mth.clamp(dir.y, -1.0, 1.0))));
   }

   private static Vec3 forwardOf(float yawDeg, float pitchDeg) {
      double yaw = Math.toRadians((double)yawDeg);
      double pitch = Math.toRadians((double)pitchDeg);
      double cosPitch = Math.cos(pitch);
      return new Vec3(-Math.sin(yaw) * cosPitch, -Math.sin(pitch), Math.cos(yaw) * cosPitch);
   }

   private static Entity resolveTarget(ServerLevel level, Entity self, CompoundTag data) {
      String lockedId = data.getString("f16_locked_target");
      if (!lockedId.isEmpty()) {
         try {
            Entity locked = level.getEntity(UUID.fromString(lockedId));
            if (locked != null && isValidTarget(self, locked) && self.distanceToSqr(locked) <= maxRangeSqr(locked)) {
               return locked;
            }
         } catch (IllegalArgumentException var10) {
         }

         data.putString("f16_locked_target", "");
      }

      int scanCooldown = data.getInt("f16_scan_cd");
      if (scanCooldown > 0) {
         data.putInt("f16_scan_cd", scanCooldown - 1);
      }

      String targetId = data.getString("f16_target");
      Entity current = null;
      if (!targetId.isEmpty()) {
         try {
            current = level.getEntity(UUID.fromString(targetId));
         } catch (IllegalArgumentException var9) {
            data.putString("f16_target", "");
         }

         if (current != null && (!isValidTarget(self, current) || self.distanceToSqr(current) > maxRangeSqr(current))) {
            current = null;
            data.putString("f16_target", "");
         }
      }

      if (scanCooldown > 0) {
         return current;
      } else {
         data.putInt("f16_scan_cd", 40);
         Entity best = findBestTarget(level, self);
         if (best != null && level.random.nextFloat() < 0.3F) {
            data.putString("f16_target", "");
            data.putInt("f16_scan_cd", 40);
            return null;
         } else {
            String bestId = best == null ? "" : best.getUUID().toString();
            if (!bestId.equals(targetId)) {
               data.putInt("f16_react_cd", 15);
            }

            data.putString("f16_target", bestId);
            return best;
         }
      }
   }

   private static double maxRangeSqr(Entity target) {
      double range = isFifthGen(target) ? 512.0 : 1024.0;
      return range * range;
   }

   private static boolean isFifthGen(Entity target) {
      Entity body = target.getVehicle() != null ? target.getVehicle() : target;
      String name = body.getClass().getSimpleName().toUpperCase(Locale.ROOT);
      return name.contains("J20")
         || name.contains("J35")
         || name.contains("F22")
         || name.contains("F35")
         || name.contains("SU57")
         || name.contains("FC31")
         || name.contains("TJGC");
   }

   private static Entity findBestTarget(ServerLevel level, Entity self) {
      List<Entity> candidates = level.getEntitiesOfClass(Entity.class, self.getBoundingBox().inflate(1024.0), e -> isValidTarget(self, e) && self.distanceToSqr(e) <= maxRangeSqr(e));
      Entity bestPlayer = null;
      double bestPlayerDistance = Double.MAX_VALUE;
      Entity bestAir = null;
      double bestAirDistance = Double.MAX_VALUE;
      Entity bestGround = null;
      double bestGroundDistance = Double.MAX_VALUE;

      for (Entity candidate : candidates) {
         double distance = self.distanceToSqr(candidate);
         if (candidate instanceof Player) {
            if (distance < bestPlayerDistance) {
               bestPlayerDistance = distance;
               bestPlayer = candidate;
            }
         } else if (isAirTarget(self, candidate)) {
            if (distance < bestAirDistance) {
               bestAirDistance = distance;
               bestAir = candidate;
            }
         } else if (distance < bestGroundDistance) {
            bestGroundDistance = distance;
            bestGround = candidate;
         }
      }

      if (bestPlayer != null) {
         return bestPlayer;
      } else {
         return bestAir != null ? bestAir : bestGround;
      }
   }

   private static boolean isValidTarget(Entity self, Entity target) {
      if (target != self && target.isAlive()) {
         if (target.getTags().contains("enemy_f16") || target.getTags().contains("enemy_ground")) {
            return false;
         } else if (!(target instanceof ItemEntity)
            && !(target instanceof ExperienceOrb)
            && !(target instanceof ArmorStand)
            && !(target instanceof AreaEffectCloud)
            && !(target instanceof HangingEntity)
            && !(target instanceof Projectile)) {
            if (target instanceof Player player && player.isSpectator()) {
               return false;
            }

            if (target.getVehicle() == self) {
               return false;
            } else if (self.getTeam() != null && target.getTeam() != null && self.getTeam().isAlliedTo(target.getTeam())) {
               return false;
            } else {
               return target instanceof LivingEntity ? true : target.getClass().getName().contains("VehicleEntity");
            }
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   private static boolean isAirTarget(Entity self, Entity target) {
      if (target instanceof Player player) {
         if (player.isFallFlying()) {
            return true;
         }

         Entity ride = player.getVehicle();
         if (ride != null && !ride.onGround() && (ride.getClass().getName().contains("VehicleEntity") || ride instanceof LivingEntity)) {
            return true;
         }
      }

      return !target.onGround() && target.getY() > self.getY() + 10.0
         ? true
         : !target.onGround() && target.getClass().getName().contains("VehicleEntity") && target.getY() > self.getY() + 5.0;
   }

   private static void fire(ServerLevel level, Entity self, CompoundTag data, Entity target, double distance, boolean air) {
      tickCooldown(data, "f16_cd_cannon");
      tickCooldown(data, "f16_cd_aim9");
      tickCooldown(data, "f16_cd_aim120");
      tickCooldown(data, "f16_cd_agm");
      tickCooldown(data, "f16_cd_bomb");
      int reaction = data.getInt("f16_react_cd");
      if (reaction > 0) {
         data.putInt("f16_react_cd", reaction - 1);
      } else {
         Vec3 aim = aimPoint(self, target);
         Vec3 toTarget = target.position().subtract(self.position());
         boolean roughlyForward = toTarget.lengthSqr() < 0.01 || self.getLookAngle().normalize().dot(toTarget.normalize()) > 0.35;
         boolean gunAligned = toTarget.lengthSqr() < 0.01 || self.getLookAngle().normalize().dot(toTarget.normalize()) > 0.82;
         if (air) {
            if (distance <= 95.0 && gunAligned && data.getInt("f16_cd_cannon") <= 0) {
               fireProjectile(level, self, "superbwarfare:small_cannon_shell", aim, 8.0, false);
               data.putInt("f16_cd_cannon", 4);
            } else if (distance <= 160.0 && roughlyForward && data.getInt("f16_cd_aim9") <= 0) {
               fireProjectile(level, self, "superbwarfare:ru_9m336_missile", aim, 0.75, false, target);
               data.putInt("f16_cd_aim9", 60);
            } else {
               if (distance <= 500.0 && roughlyForward && data.getInt("f16_cd_aim120") <= 0) {
                  fireProjectile(level, self, "dragonrise_reforge:aim120", aim, 1.0, false, target);
                  data.putInt("f16_cd_aim120", 100);
               }
            }
         } else if (self.getY() > target.getY() + 30.0 && distance < 75.0 && data.getInt("f16_cd_bomb") <= 0) {
            fireProjectile(level, self, "superbwarfare:mk_82", aim, 0.6, true);
            data.putInt("f16_cd_bomb", 100);
         } else if (distance <= 220.0 && roughlyForward && data.getInt("f16_cd_agm") <= 0) {
            fireProjectile(level, self, "superbwarfare:kh_39", aim, 1.8, false, target);
            data.putInt("f16_cd_agm", 80);
         } else {
            if (distance <= 125.0 && gunAligned && data.getInt("f16_cd_cannon") <= 0) {
               fireProjectile(level, self, "superbwarfare:small_cannon_shell", aim, 8.0, false);
               data.putInt("f16_cd_cannon", 4);
            }
         }
      }
   }

   private static Vec3 aimPoint(Entity self, Entity target) {
      Vec3 point = target.position().add(0.0, (double)target.getBbHeight() * 0.5, 0.0);
      Vec3 velocity = target.getDeltaMovement();
      if (velocity.lengthSqr() > 0.01) {
         point = point.add(velocity.scale(8.0));
      }

      return point;
   }

   private static void fireProjectile(ServerLevel level, Entity self, String entityId, Vec3 target, double speed, boolean bomb) {
      fireProjectile(level, self, entityId, target, speed, bomb, null);
   }

   private static void fireProjectile(ServerLevel level, Entity self, String entityId, Vec3 target, double speed, boolean bomb, Entity lockTarget) {
      EntityType<?> type = (EntityType<?>)ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(entityId));
      if (type != null) {
         Entity projectile = type.create(level);
         if (projectile != null) {
            Vec3 start = self.position().add(0.0, 1.4, 0.0).add(self.getLookAngle().scale(2.5));
            Vec3 dir = target.subtract(start);
            if (!(dir.lengthSqr() < 0.01)) {
               dir = dir.normalize();
               projectile.moveTo(start.x, start.y, start.z, self.getYRot(), self.getXRot());
               if (projectile instanceof Projectile p) {
                  p.setOwner(self);
               }

               if (bomb) {
                  projectile.setDeltaMovement(dir.scale(speed).add(0.0, -0.12, 0.0));
               } else {
                  projectile.setDeltaMovement(dir.scale(speed));
               }

               if (lockTarget != null) {
                  applyMissileLock(projectile, lockTarget);
                  if (entityId.contains("aim120") || entityId.contains("ru_9m336")) {
                     projectile.addTag("f16_aam");
                  }
               }

               level.addFreshEntity(projectile);
            }
         }
      }
   }

   private static void applyMissileLock(Entity missile, Entity target) {
      try {
         Method setUuid = missile.getClass().getMethod("setTargetUUID", String.class);
         setUuid.invoke(missile, target.getUUID().toString());
         Method setPos = missile.getClass().getMethod("setTargetPos", Vec3.class);
         setPos.invoke(missile, target.position());

         try {
            Method setGuide = missile.getClass().getMethod("setGuideType", int.class);
            setGuide.invoke(missile, 0);
         } catch (ReflectiveOperationException var5) {
         }
      } catch (ReflectiveOperationException var6) {
      }
   }

   public static boolean isWrecked(Entity self) {
      try {
         Method isWreck = self.getClass().getMethod("isWreck");
         Object wreck = isWreck.invoke(self);
         if (wreck instanceof Boolean && (Boolean)wreck) {
            return true;
         }
      } catch (ReflectiveOperationException var3) {
      }

      try {
         Method getHealth = self.getClass().getMethod("getHealth");
         Object health = getHealth.invoke(self);
         return health instanceof Number && ((Number)health).floatValue() <= 0.0F;
      } catch (ReflectiveOperationException var4) {
         return false;
      }
   }

   private static void setGearUp(Entity self, boolean up) {
      try {
         Method setGearUp = self.getClass().getMethod("setGearUp", boolean.class);
         setGearUp.invoke(self, up);
      } catch (ReflectiveOperationException var3) {
      }
   }

   private static void maybeReleaseDecoy(ServerLevel level, Entity self, CompoundTag data) {
      int check = data.getInt("f16_decoy_check");
      if (check > 0) {
         data.putInt("f16_decoy_check", check - 1);
      } else {
         data.putInt("f16_decoy_check", 10);
         int cooldown = data.getInt("f16_decoy_cd");
         if (cooldown > 0) {
            data.putInt("f16_decoy_cd", cooldown - 1);
         } else if (!(level.random.nextFloat() > 0.5F)) {
            for (Projectile projectile : level.getEntitiesOfClass(Projectile.class, self.getBoundingBox().inflate(96.0))) {
               if (isIncomingAam(projectile)) {
                  String targetId = missileTargetId(projectile);
                  if (self.getStringUUID().equals(targetId)) {
                     if (releaseDecoy(self)) {
                        data.putInt("f16_decoy_cd", 40);
                     }

                     return;
                  }
               }
            }
         }
      }
   }

   private static boolean isIncomingAam(Projectile projectile) {
      String name = projectile.getClass().getSimpleName();
      return name.contains("Aim120")
         || name.contains("Pl12")
         || name.contains("Pl15")
         || name.contains("Aim9")
         || name.contains("Ru9m336")
         || name.contains("Pl10")
         || name.contains("Pl8")
         || name.contains("R73");
   }

   private static String missileTargetId(Projectile projectile) {
      try {
         return projectile.getClass().getMethod("getTargetUUID").invoke(projectile) instanceof String s ? s : "";
      } catch (RuntimeException | ReflectiveOperationException var3) {
         return "";
      }
   }

   private static boolean releaseDecoy(Entity self) {
      try {
         if (self.getClass().getMethod("getDecoyCount").invoke(self) instanceof Number number && number.intValue() <= 0) {
            return false;
         }

         try {
            self.getClass().getMethod("releaseDecoy").invoke(self);
         } catch (NoSuchMethodException var3) {
            self.getClass().getMethod("releaseSmokeDecoy").invoke(self);
         }

         return true;
      } catch (RuntimeException | ReflectiveOperationException var4) {
         return false;
      }
   }

   private static void tickCooldown(CompoundTag data, String key) {
      int value = data.getInt(key);
      if (value > 0) {
         data.putInt(key, value - 1);
      }
   }
}
