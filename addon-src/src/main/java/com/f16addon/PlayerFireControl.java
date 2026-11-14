package com.f16addon;

import java.lang.reflect.Method;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;

public final class PlayerFireControl {
   public static final String PLAYER_TARGET = "f16_player_radar_target";

   private PlayerFireControl() {
   }

   public static void onEntityJoin(EntityJoinLevelEvent event) {
      if (!event.getLevel().isClientSide() && event.getEntity() instanceof Projectile projectile) {
         Entity entity = event.getEntity();
         if (isAam(entity)) {
            Player pilot = resolvePilot(projectile.getOwner());
            if (pilot != null) {
               String targetId = pilot.getPersistentData().getString("f16_player_radar_target");
               if (!targetId.isEmpty()) {
                  UUID uuid;
                  try {
                     uuid = UUID.fromString(targetId);
                  } catch (IllegalArgumentException var13) {
                     pilot.getPersistentData().remove("f16_player_radar_target");
                     return;
                  }

                  if (event.getLevel() instanceof ServerLevel level) {
                     Entity target = level.getEntity(uuid);
                     if (target != null && target.isAlive() && !target.isSpectator() && !(pilot.distanceToSqr(target) > 262144.0)) {
                        if (isRadarAam(entity) && isGroundTarget(target)) {
                           return;
                        }

                        Vec3 aim = target.position().add(0.0, (double)target.getBbHeight() * 0.5, 0.0);

                        try {
                           Method setTarget = entity.getClass().getMethod("setTargetUUID", String.class);
                           Method setPos = entity.getClass().getMethod("setTargetPos", Vec3.class);
                           Method setGuide = entity.getClass().getMethod("setGuideType", int.class);
                           setTarget.invoke(entity, uuid.toString());
                           setPos.invoke(entity, aim);
                           setGuide.invoke(entity, 0);
                        } catch (RuntimeException | ReflectiveOperationException var12) {
                        }

                        return;
                     }

                     return;
                  }
               }
            }
         }
      }
   }

   private static Player resolvePilot(Entity owner) {
      if (owner instanceof Player) {
         return (Player)owner;
      } else {
         if (owner != null) {
            Entity controller = owner.getControllingPassenger();
            if (controller instanceof Player) {
               return (Player)controller;
            }
         }

         return null;
      }
   }

   private static boolean isRadarAam(Entity entity) {
      String name = entity.getClass().getSimpleName();
      return name.contains("Aim120") || name.contains("Pl12") || name.contains("Pl15");
   }

   private static boolean isGroundTarget(Entity target) {
      Entity body = target.getVehicle() != null ? target.getVehicle() : target;
      return body.onGround();
   }

   private static boolean isAam(Entity entity) {
      String name = entity.getClass().getSimpleName();
      return name.contains("Aim9")
         || name.contains("Aim120")
         || name.contains("Ru9m336")
         || name.contains("Pl10")
         || name.contains("Pl12")
         || name.contains("Pl15")
         || name.contains("R73")
         || name.contains("Igla")
         || name.contains("Fim92");
   }
}
