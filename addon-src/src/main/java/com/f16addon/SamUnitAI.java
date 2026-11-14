package com.f16addon;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import net.minecraftforge.common.extensions.IForgeEntity;
import net.minecraftforge.registries.ForgeRegistries;

public final class SamUnitAI {
   public static final double SCAN_RANGE = 1000.0;
   private static final double FIFTH_GEN_RANGE = 400.0;
   private static final double ATTACK_RANGE = 1000.0;
   private static final int SCAN_INTERVAL = 10;
   private static final int FIRE_INTERVAL = 40;
   private static final double MIN_TARGET_HEIGHT = 8.0;
   private static final float IR_DAMAGE = 260.0F;
   private static final float IR_EXPLOSION_DAMAGE = 160.0F;
   private static final float IR_EXPLOSION_RADIUS = 6.0F;
   private static final Map<String, Optional<Method>> METHOD_CACHE = new ConcurrentHashMap<>();

   private SamUnitAI() {
   }

   public static void tick(ServerLevel var0, Entity var1) {
      CompoundTag var2 = ((IForgeEntity)var1).getPersistentData();
      if (isWrecked(var1)) {
         clearTarget(var1, var2);
      } else {
         int var3 = var2.getInt("sam_fire_cd");
         if (var3 > 0) {
            var2.putInt("sam_fire_cd", var3 - 1);
         }

         Entity var4 = resolveTarget(var0, var1, var2);
         if (var4 != null && isValidCachedTarget(var1, var4)) {
            applyTarget(var1, var4);
            Vec3 var5 = var4.position().add(0.0, (double)var4.getBbHeight() * 0.5, 0.0);
            var5 = var5.add(var4.getDeltaMovement().scale(8.0));
            aimTurret(var1, var5);
            double var6 = var1.position().distanceTo(var4.position());
            if (var2.getInt("sam_fire_cd") <= 0 && var6 <= 1000.0 && heightAboveGround(var0, var4) >= 8.0 && hasLineOfSight(var0, var1, var5)) {
               fire(var0, var1, var2, var4, var5);
            }
         } else {
            clearTarget(var1, var2);
         }
      }
   }

   private static Entity resolveTarget(ServerLevel var0, Entity var1, CompoundTag var2) {
      int var3 = var2.getInt("sam_scan_cd");
      if (var3 > 0) {
         var2.putInt("sam_scan_cd", var3 - 1);
      }

      String var4 = var2.getString("sam_target");
      if (!var4.isEmpty()) {
         try {
            Entity var5 = var0.getEntity(UUID.fromString(var4));
            if (var5 != null && var5.isAlive()) {
               double var6 = effectiveRange(var5);
               if (var1.distanceToSqr(var5) <= var6 * var6 && var3 > 0) {
                  return var5;
               }
            }
         } catch (IllegalArgumentException var18) {
         }
      }

      if (var3 > 0) {
         var2.putString("sam_target", "");
         return null;
      } else {
         var2.putInt("sam_scan_cd", 10);
         List<Entity> var19 = var0.getEntitiesOfClass(Entity.class, var1.getBoundingBox().inflate(1000.0), var2x -> {
            double var3x = effectiveRange(var2x);
            return var1.distanceToSqr(var2x) <= var3x * var3x && isValidTarget(var0, var1, var2x);
         });
         Entity var20 = null;
         Entity var7 = null;
         double var8 = Double.MAX_VALUE;
         double var10 = Double.MAX_VALUE;

         for (Entity var13 : var19) {
            double var14 = var1.distanceToSqr(var13);
            double var16 = effectiveRange(var13);
            if (!(var14 > var16 * var16)) {
               if (isMissile(var13)) {
                  if (var14 < var8) {
                     var20 = var13;
                     var8 = var14;
                  }
               } else if (var14 < var10) {
                  var7 = var13;
                  var10 = var14;
               }
            }
         }

         Entity var21 = var20 != null ? var20 : var7;
         var2.putString("sam_target", var21 == null ? "" : var21.getUUID().toString());
         return var21;
      }
   }

   private static boolean isValidCachedTarget(Entity var0, Entity var1) {
      if (var1 != var0 && var1.isAlive() && !var1.isSpectator()) {
         if (!var1.getTags().contains("enemy_ground") && !var1.getTags().contains("enemy_sam") && !var1.getTags().contains("enemy_f16")) {
            Team var2 = var1.getTeam();
            Team var3 = var0.getTeam();
            if (var3 == null || var2 == null || var3 != var2 && !var3.isAlliedTo(var2)) {
               if (var2 != null && var2.getName().equals("enemy")) {
                  return false;
               } else if (var1 instanceof Projectile var6) {
                  Entity var5 = var6.getOwner();
                  return var5 != null && isFriendlyOwner(var0, var5) ? false : isAirDefenseProjectile(var1);
               } else {
                  Entity var4 = var1.getVehicle() != null ? var1.getVehicle() : var1;
                  return isEnemyAircraft(var4) && !var4.onGround();
               }
            } else {
               return false;
            }
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   private static boolean isValidTarget(ServerLevel var0, Entity var1, Entity var2) {
      if (var2 != var1 && var2.isAlive() && !var2.isSpectator()) {
         if (!var2.getTags().contains("enemy_ground") && !var2.getTags().contains("enemy_sam") && !var2.getTags().contains("enemy_f16")) {
            Team var3 = var2.getTeam();
            Team var4 = var1.getTeam();
            if (var4 == null || var3 == null || var4 != var3 && !var4.isAlliedTo(var3)) {
               if (var3 != null && var3.getName().equals("enemy")) {
                  return false;
               } else if (var2 instanceof Projectile var7) {
                  Entity var6 = var7.getOwner();
                  return var6 != null && isFriendlyOwner(var1, var6)
                     ? false
                     : isAirDefenseProjectile(var2) && heightAboveGround(var0, var2) >= 8.0 && hasLineOfSight(var0, var1, var2);
               } else if (!(var2 instanceof ItemEntity)
                  && !(var2 instanceof ExperienceOrb)
                  && !(var2 instanceof ArmorStand)
                  && !(var2 instanceof AreaEffectCloud)
                  && !(var2 instanceof HangingEntity)) {
                  Entity var5 = var2.getVehicle() != null ? var2.getVehicle() : var2;
                  return isEnemyAircraft(var5) && !var5.onGround() && heightAboveGround(var0, var2) >= 8.0 && hasLineOfSight(var0, var1, var2);
               } else {
                  return false;
               }
            } else {
               return false;
            }
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   private static double heightAboveGround(ServerLevel var0, Entity var1) {
      int var2 = var0.getHeight(Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(var1.getX()), Mth.floor(var1.getZ()));
      return var1.getY() - (double)var2;
   }

   private static boolean hasLineOfSight(ServerLevel var0, Entity var1, Entity var2) {
      Vec3 var3 = var2.position().add(0.0, (double)var2.getBbHeight() * 0.5, 0.0);
      return hasLineOfSight(var0, var1, var3);
   }

   private static boolean hasLineOfSight(ServerLevel var0, Entity var1, Vec3 var2) {
      Vec3 var3 = shootPos(var1, "AAMissile");
      BlockHitResult var4 = var0.clip(new ClipContext(var3, var2, Block.COLLIDER, Fluid.NONE, var1));
      return var4.getType() == Type.MISS ? true : var4.getLocation().distanceToSqr(var3) >= var3.distanceToSqr(var2) - 1.0;
   }

   private static double effectiveRange(Entity var0) {
      return isFifthGen(var0) ? 400.0 : 1000.0;
   }

   private static boolean isFifthGen(Entity var0) {
      Entity var1 = var0.getVehicle() != null ? var0.getVehicle() : var0;
      String var2 = var1.getClass().getSimpleName().toUpperCase(Locale.ROOT);
      return var2.contains("J20")
         || var2.contains("J35")
         || var2.contains("F22")
         || var2.contains("F35")
         || var2.contains("SU57")
         || var2.contains("FC31")
         || var2.contains("TJGC");
   }

   private static boolean isFriendlyOwner(Entity var0, Entity var1) {
      if (var1 == var0) {
         return true;
      } else {
         Team var2 = var0.getTeam();
         Team var3 = var1.getTeam();
         return var2 == null || var3 == null || var2 != var3 && !var2.isAlliedTo(var3)
            ? var1.getTags().contains("enemy_f16") || var1.getTags().contains("enemy_ground") || var1.getTags().contains("enemy_sam")
            : true;
      }
   }

   private static boolean isAirDefenseProjectile(Entity var0) {
      if (isMissile(var0)) {
         return true;
      } else {
         String var1 = var0.getClass().getSimpleName().toUpperCase(Locale.ROOT);
         return var1.contains("SHELL")
            || var1.contains("CANNON")
            || var1.contains("ARTILLERY")
            || var1.contains("MORTAR")
            || var1.contains("ROCKET")
            || var1.contains("RPG")
            || var1.contains("GRENADE");
      }
   }

   private static boolean isEnemyAircraft(Entity var0) {
      if (!(var0 instanceof Projectile) && !(var0 instanceof Player)) {
         try {
            Method var1 = cachedMethod(var0.getClass(), "getVehicleType");
            if (var1 != null) {
               Object var2 = var1.invoke(var0);
               String var3 = String.valueOf(var2).toUpperCase(Locale.ROOT);
               return var3.equals("AIRPLANE") || var3.equals("HELICOPTER") || var3.equals("DRONE") || var3.equals("AIRSHIP");
            }
         } catch (RuntimeException | ReflectiveOperationException var4) {
         }

         String var5 = var0.getClass().getSimpleName().toUpperCase(Locale.ROOT);
         return var5.contains("AIRPLANE")
            || var5.contains("AIRCRAFT")
            || var5.contains("HELICOPTER")
            || var5.contains("DRONE")
            || var5.contains("AIRSHIP")
            || var5.contains("F16")
            || var5.contains("F14")
            || var5.contains("F15")
            || var5.contains("FA18")
            || var5.contains("A10")
            || var5.contains("AC130")
            || var5.contains("J10")
            || var5.contains("J11")
            || var5.contains("J15")
            || var5.contains("J16")
            || var5.contains("J20")
            || var5.contains("J35")
            || var5.contains("JAS39")
            || var5.contains("REFALE")
            || var5.contains("SU24")
            || var5.contains("MIG")
            || var5.contains("AH1")
            || var5.contains("AH64")
            || var5.contains("MI24")
            || var5.contains("Z10")
            || var5.contains("KIROV");
      } else {
         return false;
      }
   }

   private static boolean isMissile(Entity var0) {
      String var1 = var0.getClass().getSimpleName().toUpperCase();
      return var1.contains("MISSILE")
         || var1.contains("AIM120")
         || var1.contains("AIM9")
         || var1.contains("PL10")
         || var1.contains("PL12")
         || var1.contains("PL15")
         || var1.contains("RU9")
         || var1.contains("IGLA")
         || var1.contains("FIM")
         || var1.contains("AGM")
         || var1.contains("KH")
         || var1.contains("JAVELIN")
         || var1.contains("HELLFIRE")
         || var1.contains("TOW");
   }

   private static void aimTurret(Entity var0, Vec3 var1) {
      try {
         Method var2 = cachedMethod(var0.getClass(), "turretAutoAimFromVector", Vec3.class);
         if (var2 != null) {
            var2.invoke(var0, var1);
         }

         Method var3 = cachedMethod(var0.getClass(), "adjustTurretAngle");
         if (var3 != null) {
            var3.invoke(var0);
         }
      } catch (RuntimeException | ReflectiveOperationException var4) {
      }
   }

   private static void fire(ServerLevel var0, Entity var1, CompoundTag var2, Entity var3, Vec3 var4) {
      if (var2.getInt("sam_fire_cd") <= 0) {
         Vec3 var5 = shootPos(var1, "AAMissile");
         Vec3 var6 = var4.subtract(var5);
         if (!(var6.lengthSqr() < 0.01)) {
            var6 = var6.normalize();
            EntityType var7 = (EntityType)ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("superbwarfare", "ru_9m336_missile"));
            if (var7 != null) {
               Entity var8 = var7.create(var0);
               if (var8 != null) {
                  var8.moveTo(
                     var5.x,
                     var5.y,
                     var5.z,
                     (float)Math.toDegrees(Math.min(-var6.x, var6.z)),
                     (float)(-Math.toDegrees(Math.asin(Mth.clamp(var6.y, -1.0, 1.0))))
                  );
                  var8.setDeltaMovement(var6.scale(3.0));
                  if (var8 instanceof Projectile var9) {
                     var9.setOwner(var1);
                  }

                  setFloat(var8, "setDamageValue", 260.0F);
                  setFloat(var8, "setExplosionDamageValue", 160.0F);
                  setFloat(var8, "setExplosionRadiusValue", 6.0F);
                  setString(var8, "setTargetUUID", var3.getStringUUID());
                  setVec(var8, "setTargetPos", var4);
                  setInt(var8, "setGuideType", 0);
                  if (var0.addFreshEntity(var8)) {
                     var2.putInt("sam_fire_cd", 40);
                  }
               }
            }
         }
      }
   }

   private static Vec3 shootPos(Entity var0, String var1) {
      try {
         Method var2 = cachedMethod(var0.getClass(), "getShootPos", String.class, float.class);
         if (var2 != null) {
            Object var3 = var2.invoke(var0, var1, 1.0F);
            if (var3 instanceof Vec3) {
               return (Vec3)var3;
            }
         }
      } catch (RuntimeException | ReflectiveOperationException var5) {
      }

      return var0.position().add(0.0, 1.5, 0.0).add(var0.getLookAngle().scale(2.0));
   }

   private static void applyTarget(Entity var0, Entity var1) {
      setString(var0, "setAiTurretTargetUUID", "");
      setString(var0, "setAiPassengerWeaponTargetUUID", "");
   }

   private static void clearTarget(Entity var0, CompoundTag var1) {
      var1.putString("sam_target", "");
      setString(var0, "setAiTurretTargetUUID", "");
      setString(var0, "setAiPassengerWeaponTargetUUID", "");
   }

   private static void setString(Object var0, String var1, String var2) {
      try {
         Method var3 = cachedMethod(var0.getClass(), var1, String.class);
         if (var3 != null) {
            var3.invoke(var0, var2);
         }
      } catch (RuntimeException | ReflectiveOperationException var4) {
      }
   }

   private static void setVec(Object var0, String var1, Vec3 var2) {
      try {
         Method var3 = cachedMethod(var0.getClass(), var1, Vec3.class);
         if (var3 != null) {
            var3.invoke(var0, var2);
         }
      } catch (RuntimeException | ReflectiveOperationException var4) {
      }
   }

   private static void setInt(Object var0, String var1, int var2) {
      try {
         Method var3 = cachedMethod(var0.getClass(), var1, int.class);
         if (var3 != null) {
            var3.invoke(var0, var2);
         }
      } catch (RuntimeException | ReflectiveOperationException var4) {
      }
   }

   private static void setFloat(Object var0, String var1, float var2) {
      Class var3 = var0.getClass();

      while (var3 != null) {
         try {
            Method var4 = var3.getDeclaredMethod(var1, float.class);
            var4.setAccessible(true);
            var4.invoke(var0, var2);
            return;
         } catch (NoSuchMethodException var5) {
            var3 = var3.getSuperclass();
         } catch (RuntimeException | ReflectiveOperationException var6) {
            return;
         }
      }
   }

   private static boolean isWrecked(Entity var0) {
      try {
         Method var1 = cachedMethod(var0.getClass(), "isWreck");
         if (var1 != null && Boolean.TRUE.equals(var1.invoke(var0))) {
            return true;
         }
      } catch (RuntimeException | ReflectiveOperationException var4) {
      }

      try {
         Method var6 = cachedMethod(var0.getClass(), "getHealth");
         if (var6 != null) {
            if (var6.invoke(var0) instanceof Number var3 && var3.floatValue() <= 0.0F) {
               return true;
            }

            return false;
         }
      } catch (RuntimeException | ReflectiveOperationException var5) {
      }

      return false;
   }

   private static Method cachedMethod(Class<?> var0, String var1, Class<?>... var2) {
      String var3 = var0.getName() + "#" + var1 + "#" + var2.length;
      Optional var4 = METHOD_CACHE.get(var3);
      if (var4 == null) {
         try {
            var4 = Optional.of(var0.getMethod(var1, var2));
         } catch (RuntimeException | ReflectiveOperationException var6) {
            var4 = Optional.empty();
         }

         METHOD_CACHE.put(var3, var4);
      }

      return (Method)var4.orElse(null);
   }
}
