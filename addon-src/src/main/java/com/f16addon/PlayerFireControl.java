package com.f16addon;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;

/** Hands the pilot's manually selected target ({@code /f16addon playerlock}) to air-to-air missiles they launch. */
public final class PlayerFireControl {
   public static final String PLAYER_TARGET = "f16_player_radar_target";
   private static final double MAX_RANGE_SQR = 512.0 * 512.0;

   private PlayerFireControl() {
   }

   public static void onEntityJoin(EntityJoinLevelEvent event) {
      if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof Projectile projectile)) {
         return;
      }

      if (!Classify.is(projectile, Classify.AAM) || event.loadedFromDisk()) {
         return;
      }

      Player pilot = resolvePilot(projectile.getOwner());
      if (pilot == null) {
         return;
      }

      String targetId = pilot.getPersistentData().getString(PLAYER_TARGET);
      if (targetId.isEmpty()) {
         return;
      }

      Entity target = Targeting.byUuid(level, targetId);
      if (target == null || !target.isAlive() || target.isSpectator() || pilot.distanceToSqr(target) > MAX_RANGE_SQR) {
         return;
      }

      // Radar AAMs have MinTargetHeight=8 in the vehicle data; mirror that so a ground lock is never forced on them.
      if (Classify.is(projectile, Classify.RADAR_AAM) && target.getRootVehicle().onGround()) {
         return;
      }

      Sbw.lockMissile(projectile, target, WorldQueries.center(target));
   }

   private static Player resolvePilot(Entity owner) {
      if (owner instanceof Player player) {
         return player;
      }

      return owner != null && owner.getControllingPassenger() instanceof Player player ? player : null;
   }
}
