package com.f16addon;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

public final class MissileLockLoss {
   private static final int CHECK_INTERVAL = 20;
   private static final float LOSS_CHANCE = 0.05F;
   private static final Map<Class<?>, MissileLockLoss.Methods> CACHE = new ConcurrentHashMap<>();

   private MissileLockLoss() {
   }

   public static void tick(Projectile projectile) {
      if (!projectile.level().isClientSide() && projectile.tickCount % 20 == 0) {
         if (!projectile.getPersistentData().getBoolean("f16_lock_lost")) {
            MissileLockLoss.Methods methods = CACHE.computeIfAbsent(projectile.getClass(), MissileLockLoss::find);
            if (methods != null) {
               try {
                  if (!(methods.getUuid.invoke(projectile) instanceof String value) || value.isEmpty() || "none".equals(value)) {
                     return;
                  }

                  if (!(projectile.level().random.nextFloat() >= 0.05F)) {
                     if (!(methods.getPos.invoke(projectile) instanceof Vec3 lastKnown)) {
                        return;
                     }

                     methods.setTargetVec.invoke(projectile, lastKnown);
                     if (methods.setLostTarget != null) {
                        methods.setLostTarget.invoke(projectile, true);
                     }

                     projectile.getPersistentData().putBoolean("f16_lock_lost", true);
                  }
               } catch (RuntimeException | ReflectiveOperationException var6) {
               }
            }
         }
      }
   }

   private static MissileLockLoss.Methods find(Class<?> clazz) {
      try {
         Method getUuid = clazz.getMethod("getTargetUUID");
         Method getPos = clazz.getMethod("getTargetPos");
         Method setTargetVec = clazz.getMethod("setTargetVec", Vec3.class);

         Method setLostTarget;
         try {
            setLostTarget = clazz.getMethod("setLostTarget", boolean.class);
         } catch (NoSuchMethodException var6) {
            setLostTarget = null;
         }

         return new MissileLockLoss.Methods(getUuid, getPos, setTargetVec, setLostTarget);
      } catch (RuntimeException | NoSuchMethodException var7) {
         return null;
      }
   }

   private static record Methods(Method getUuid, Method getPos, Method setTargetVec, Method setLostTarget) {
   }
}
