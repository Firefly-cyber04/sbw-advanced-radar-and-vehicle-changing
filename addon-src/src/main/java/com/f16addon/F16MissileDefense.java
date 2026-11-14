package com.f16addon;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public final class F16MissileDefense {
   private static final String AAM_TAG = "f16_aam";
   private static final double DECOY_RANGE = 48.0;

   private F16MissileDefense() {
   }

   public static void tick(ServerLevel level, Entity missile) {
      if (missile.isAlive() && !missile.getPersistentData().getBoolean("f16_decoyed")) {
         int cooldown = missile.getPersistentData().getInt("f16_decoy_cd");
         if (cooldown > 0) {
            missile.getPersistentData().putInt("f16_decoy_cd", cooldown - 1);
         } else {
            missile.getPersistentData().putInt("f16_decoy_cd", 10);
            String targetId = getString(missile, "getTargetUUID");
            if (targetId != null && !targetId.isEmpty() && !"none".equals(targetId)) {
               Entity target;
               try {
                  target = level.getEntity(UUID.fromString(targetId));
               } catch (IllegalArgumentException var10) {
                  return;
               }

               if (target != null && target.isAlive()) {
                  List<Entity> decoys = level.getEntitiesOfClass(Entity.class, target.getBoundingBox().inflate(48.0), e -> isDecoy(missile, e));
                  if (!decoys.isEmpty()) {
                     decoys.sort(Comparator.comparingDouble(missile::distanceToSqr));
                     float chance = Math.min(0.75F, 0.32F + 0.1F * (float)decoys.size());
                     if (!(level.random.nextFloat() >= chance)) {
                        Entity decoy = decoys.get(0);
                        setString(missile, "setTargetUUID", decoy.getUUID().toString());
                        setVec(missile, "setTargetPos", decoy.position());
                        setBoolean(missile, "setDistracted", true);

                        try {
                           Method guide = missile.getClass().getMethod("setGuideType", int.class);
                           guide.invoke(missile, 0);
                        } catch (ReflectiveOperationException var9) {
                        }

                        CompoundTag data = missile.getPersistentData();
                        data.putBoolean("f16_decoyed", true);
                        data.putString("f16_decoy_source", targetId);
                     }
                  }
               }
            }
         }
      }
   }

   private static boolean isDecoy(Entity missile, Entity entity) {
      if (entity != missile && entity.isAlive()) {
         String name = entity.getClass().getSimpleName();
         return name.contains("FlareDecoy") || name.contains("SmokeDecoy") || name.contains("DecoyEntity");
      } else {
         return false;
      }
   }

   private static String getString(Entity entity, String method) {
      try {
         Object value = entity.getClass().getMethod(method).invoke(entity);
         return value instanceof String ? (String)value : null;
      } catch (ReflectiveOperationException var3) {
         return null;
      }
   }

   private static void setString(Entity entity, String method, String value) {
      try {
         entity.getClass().getMethod(method, String.class).invoke(entity, value);
      } catch (ReflectiveOperationException var4) {
      }
   }

   private static void setVec(Entity entity, String method, Vec3 value) {
      try {
         entity.getClass().getMethod(method, Vec3.class).invoke(entity, value);
      } catch (ReflectiveOperationException var4) {
      }
   }

   private static void setBoolean(Entity entity, String method, boolean value) {
      try {
         entity.getClass().getMethod(method, boolean.class).invoke(entity, value);
      } catch (ReflectiveOperationException var4) {
      }
   }
}
