package com.f16addon;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.ClipContext.Block;
import net.minecraft.world.level.ClipContext.Fluid;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult.Type;
import net.minecraft.world.scores.Team;
import net.minecraftforge.registries.ForgeRegistries;

public final class GroundUnitAI {
   public static final double SCAN_RANGE = 328.0;
   private static final double ATTACK_RANGE = 160.0;
   private static final int SCAN_INTERVAL = 20;
   private static final double ADVANCE_STANDOFF = 48.0;
   private static final double FOLLOW_STANDOFF = 24.0;
   private static final double MAX_SPEED = 0.18;
   private static final double PATROL_RADIUS = 512.0;
   private static final double PATROL_MIN_RADIUS = 64.0;
   private static final float MAX_YAW_STEP = 2.2F;

   private GroundUnitAI() {
   }

   public static void tick(ServerLevel level, Entity self) {
      CompoundTag data = self.getPersistentData();
      if (isWrecked(self)) {
         clearTarget(self, data);
         self.setDeltaMovement(Vec3.ZERO);
      } else {
         String mode = data.getString("ground_mode");
         if (mode.isEmpty()) {
            mode = "advance";
            data.putString("ground_mode", mode);
         }

         if (mode.equals("stop")) {
            clearTarget(self, data);
         } else {
            int fireCooldown = data.getInt("ground_fire_cd");
            if (fireCooldown > 0) {
               data.putInt("ground_fire_cd", fireCooldown - 1);
            }

            Entity target = resolveTarget(level, self, data);
            if (target == null) {
               clearTarget(self, data);
               if (!mode.equals("hold")) {
                  patrol(level, self, data);
               }
            } else {
               applyTarget(self, target);
               Vec3 aim = target.position().add(0.0, (double)target.getBbHeight() * 0.5, 0.0);
               aim = aim.add(target.getDeltaMovement().scale(8.0));
               aimTurret(self, aim);
               double distance = self.position().distanceTo(target.position());
               Vec3 toTarget = target.position().subtract(self.position());
               if (!(toTarget.lengthSqr() < 0.001)) {
                  face(self, toTarget);
                  if (!mode.equals("hold")) {
                     double standoff = mode.equals("follow") ? 24.0 : 48.0;
                     if (distance > standoff) {
                        Vec3 yawVec = new Vec3(-Math.sin(Math.toRadians((double)self.getYRot())), 0.0, Math.cos(Math.toRadians((double)self.getYRot())));
                        move(self, level, self.position().add(yawVec.scale(0.18)));
                     }
                  }

                  if (distance <= 160.0 && hasLineOfSight(level, self, aim)) {
                     fire(level, self, data, aim);
                  }
               }
            }
         }
      }
   }

   private static void patrol(ServerLevel level, Entity self, CompoundTag data) {
      ensureHome(self, data);
      double pointX = data.getDouble("ground_patrol_x");
      double pointZ = data.getDouble("ground_patrol_z");
      boolean needPoint = !data.contains("ground_patrol_x") || self.distanceToSqr(pointX, self.getY(), pointZ) < 64.0;
      if (needPoint) {
         double angle = level.random.nextDouble() * Math.PI * 2.0;
         double radius = 64.0 + level.random.nextDouble() * 448.0;
         pointX = data.getDouble("ground_home_x") + Math.cos(angle) * radius;
         pointZ = data.getDouble("ground_home_z") + Math.sin(angle) * radius;
         data.putDouble("ground_patrol_x", pointX);
         data.putDouble("ground_patrol_z", pointZ);
      }

      Vec3 point = new Vec3(pointX, 0.0, pointZ);
      Vec3 toPoint = point.subtract(self.position());
      if (!(toPoint.lengthSqr() < 4.0)) {
         face(self, toPoint);
         Vec3 yawVec = new Vec3(-Math.sin(Math.toRadians((double)self.getYRot())), 0.0, Math.cos(Math.toRadians((double)self.getYRot())));
         move(self, level, self.position().add(yawVec.scale(0.135)));
      }
   }

   private static void ensureHome(Entity self, CompoundTag data) {
      if (!data.contains("ground_home_x")) {
         data.putDouble("ground_home_x", self.getX());
         data.putDouble("ground_home_z", self.getZ());
      }
   }

   private static void face(Entity self, Vec3 toTarget) {
      double yaw = Math.toDegrees(Math.atan2(-toTarget.x, toTarget.z));
      float oldYaw = self.getYRot();
      float newYaw = smoothAngle(oldYaw, (float)yaw, 2.2F);
      self.setYRot(newYaw);
      self.yRotO = oldYaw;
   }

   private static void move(Entity self, ServerLevel level, Vec3 next) {
      int ground = level.getHeight(Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(next.x), Mth.floor(next.z));
      self.setPos(next.x, (double)ground + 0.1, next.z);
      self.setDeltaMovement(Vec3.ZERO);
      self.hurtMarked = true;
   }

   private static void aimTurret(Entity self, Vec3 aim) {
      try {
         Method autoAim = self.getClass().getMethod("turretAutoAimFromVector", Vec3.class);
         autoAim.invoke(self, aim);
         Method adjust = self.getClass().getMethod("adjustTurretAngle");
         adjust.invoke(self);
      } catch (RuntimeException | ReflectiveOperationException var4) {
      }
   }

   private static boolean hasLineOfSight(ServerLevel level, Entity self, Vec3 aim) {
      boolean bmpt = self.getClass().getSimpleName().toUpperCase().contains("BMPT");
      Vec3 from = shootPos(self, bmpt ? "Cannon1" : "Cannon");
      BlockHitResult hit = level.clip(new ClipContext(from, aim, Block.COLLIDER, Fluid.NONE, self));
      return hit.getType() == Type.MISS ? true : hit.getLocation().distanceToSqr(from) >= from.distanceToSqr(aim) - 1.0;
   }

   private static void fire(ServerLevel level, Entity self, CompoundTag data, Vec3 aim) {
      if (data.getInt("ground_fire_cd") <= 0) {
         boolean bmpt = self.getClass().getSimpleName().toUpperCase().contains("BMPT");
         String weapon = bmpt ? "Cannon1" : "Cannon";
         String projectileId = bmpt ? "small_cannon_shell" : "cannon_shell";
         float damage = bmpt ? 15.0F : 250.0F;
         float explosionDamage = bmpt ? 18.0F : 120.0F;
         float explosionRadius = bmpt ? 5.0F : 10.0F;
         double speed = bmpt ? 6.0 : 12.0;
         int delay = bmpt ? 6 : 30;
         Vec3 shootPos = shootPos(self, weapon);
         Vec3 direction = aim.subtract(shootPos);
         if (!(direction.lengthSqr() < 0.01)) {
            direction = direction.normalize();
            EntityType<?> type = (EntityType<?>)ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("superbwarfare", projectileId));
            if (type != null) {
               Entity projectile = type.create(level);
               if (projectile != null) {
                  projectile.moveTo(
                     shootPos.x,
                     shootPos.y,
                     shootPos.z,
                     (float)Math.toDegrees(Math.atan2(-direction.x, direction.z)),
                     (float)(-Math.toDegrees(Math.asin(Mth.clamp(direction.y, -1.0, 1.0))))
                  );
                  projectile.setDeltaMovement(direction.scale(speed));
                  if (projectile instanceof Projectile p) {
                     p.setOwner(self);
                  }

                  setFloat(projectile, "setDamageValue", damage);
                  setFloat(projectile, "setExplosionDamageValue", explosionDamage);
                  setFloat(projectile, "setExplosionRadiusValue", explosionRadius);
                  if (level.addFreshEntity(projectile)) {
                     data.putInt("ground_fire_cd", delay);
                  }
               }
            }
         }
      }
   }

   private static Vec3 shootPos(Entity self, String weapon) {
      try {
         Object value = self.getClass().getMethod("getShootPos", String.class, float.class).invoke(self, weapon, 1.0F);
         if (value instanceof Vec3) {
            return (Vec3)value;
         }
      } catch (RuntimeException | ReflectiveOperationException var4) {
      }

      Vec3 look = self.getLookAngle();
      return self.position().add(0.0, 1.2, 0.0).add(look.scale(2.0));
   }

   private static void setFloat(Object target, String methodName, float value) {
      Class<?> type = target.getClass();

      while (type != null) {
         try {
            Method method = type.getDeclaredMethod(methodName, float.class);
            method.setAccessible(true);
            method.invoke(target, value);
            return;
         } catch (NoSuchMethodException var5) {
            type = type.getSuperclass();
         } catch (RuntimeException | ReflectiveOperationException var6) {
            return;
         }
      }
   }

   private static Entity resolveTarget(ServerLevel level, Entity self, CompoundTag data) {
      int cooldown = data.getInt("ground_scan_cd");
      if (cooldown > 0) {
         data.putInt("ground_scan_cd", cooldown - 1);
      }

      String targetId = data.getString("ground_target");
      if (!targetId.isEmpty()) {
         try {
            Entity current = level.getEntity(UUID.fromString(targetId));
            if (current != null && isValidTarget(self, current) && self.distanceToSqr(current) <= 107584.0) {
               return current;
            }
         } catch (IllegalArgumentException var13) {
         }

         data.putString("ground_target", "");
      }

      if (cooldown > 0) {
         return null;
      } else {
         data.putInt("ground_scan_cd", 20);
         List<Entity> candidates = level.getEntitiesOfClass(Entity.class, self.getBoundingBox().inflate(328.0), e -> isValidTarget(self, e));
         Entity best = null;
         double bestDistance = Double.MAX_VALUE;

         for (Entity candidate : candidates) {
            if (!(self.distanceToSqr(candidate) > 107584.0)) {
               double distance = self.distanceToSqr(candidate);
               if (candidate instanceof Player) {
                  if (!(best instanceof Player) || distance < bestDistance) {
                     best = candidate;
                     bestDistance = distance;
                  }
               } else if (!(best instanceof Player) && distance < bestDistance) {
                  best = candidate;
                  bestDistance = distance;
               }
            }
         }

         data.putString("ground_target", best == null ? "" : best.getUUID().toString());
         return best;
      }
   }

   private static boolean isValidTarget(Entity self, Entity target) {
      if (target != self && target.isAlive() && !target.isSpectator()) {
         if (target.getTags().contains("enemy_ground") || target.getTags().contains("enemy_f16")) {
            return false;
         } else if (!(target instanceof ItemEntity)
            && !(target instanceof ExperienceOrb)
            && !(target instanceof ArmorStand)
            && !(target instanceof AreaEffectCloud)
            && !(target instanceof HangingEntity)
            && !(target instanceof Projectile)) {
            Team team = target.getTeam();
            Team own = self.getTeam();
            return own == null || team == null || own != team && !own.isAlliedTo(team) ? team == null || !team.getName().equals("enemy") : false;
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   private static void applyTarget(Entity self, Entity target) {
      try {
         Method turret = self.getClass().getMethod("setAiTurretTargetUUID", String.class);
         turret.invoke(self, target.getStringUUID());
         Method passenger = self.getClass().getMethod("setAiPassengerWeaponTargetUUID", String.class);
         passenger.invoke(self, target.getStringUUID());
      } catch (RuntimeException | ReflectiveOperationException var4) {
      }
   }

   private static void clearTarget(Entity self, CompoundTag data) {
      data.putString("ground_target", "");

      try {
         Method turret = self.getClass().getMethod("setAiTurretTargetUUID", String.class);
         turret.invoke(self, "");
         Method passenger = self.getClass().getMethod("setAiPassengerWeaponTargetUUID", String.class);
         passenger.invoke(self, "");
      } catch (RuntimeException | ReflectiveOperationException var4) {
      }
   }

   private static boolean isWrecked(Entity self) {
      try {
         Object result = self.getClass().getMethod("isWreck").invoke(self);
         if (Boolean.TRUE.equals(result)) {
            return true;
         }
      } catch (RuntimeException | ReflectiveOperationException var3) {
      }

      try {
         if (self.getClass().getMethod("getHealth").invoke(self) instanceof Number number && number.floatValue() <= 0.0F) {
            return true;
         }

         return false;
      } catch (RuntimeException | ReflectiveOperationException var4) {
         return false;
      }
   }

   private static float smoothAngle(float current, float target, float maxStep) {
      float delta = Mth.wrapDegrees(target - current);
      return current + Mth.clamp(delta, -maxStep, maxStep);
   }
}
