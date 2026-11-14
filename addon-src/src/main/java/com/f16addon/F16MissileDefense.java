package com.f16addon;

import java.util.Comparator;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;

/** Lets flares / smoke decoys spoof air-to-air missiles fired by enemy jets. */
public final class F16MissileDefense {
   public static final String AAM_TAG = "f16_aam";
   private static final double DECOY_RANGE = 48.0;
   private static final int CHECK_INTERVAL = 10;

   private F16MissileDefense() {
   }

   /** Missiles launched by an enemy jet (by our flight AI, or by a crew core / anything else sitting in the jet). */
   public static boolean isEnemyAam(Entity entity) {
      if (entity.getTags().contains(AAM_TAG)) {
         return true;
      }

      if (!(entity instanceof Projectile projectile) || !Classify.is(entity, Classify.IR_AAM | Classify.RADAR_AAM)) {
         return false;
      }

      Entity owner = projectile.getOwner();
      return owner != null && owner.getTags().contains(UnitTracker.TAG_F16);
   }

   public static void tick(ServerLevel level, Entity missile) {
      // Staggered per missile instead of a per-missile NBT countdown.
      if ((missile.tickCount + missile.getId()) % CHECK_INTERVAL != 0 || !missile.isAlive()) {
         return;
      }

      CompoundTag data = missile.getPersistentData();
      if (data.getBoolean("f16_decoyed")) {
         return;
      }

      String targetId = Sbw.targetUuid(missile);
      Entity target = Targeting.byUuid(level, targetId);
      if (target == null || !target.isAlive()) {
         return;
      }

      List<Entity> decoys = level.getEntities(missile, target.getBoundingBox().inflate(DECOY_RANGE), e -> e.isAlive() && Classify.is(e, Classify.DECOY));
      if (decoys.isEmpty()) {
         return;
      }

      float chance = Math.min(0.75F, 0.32F + 0.1F * decoys.size());
      if (level.random.nextFloat() >= chance) {
         return;
      }

      decoys.sort(Comparator.comparingDouble(missile::distanceToSqr));
      Entity decoy = decoys.get(0);
      Sbw.call(missile, Sbw.SET_TARGET_UUID, decoy.getStringUUID());
      Sbw.call(missile, Sbw.SET_TARGET_POS, decoy.position());
      Sbw.call(missile, Sbw.SET_DISTRACTED, true);
      Sbw.call(missile, Sbw.SET_GUIDE_TYPE, 0);
      data.putBoolean("f16_decoyed", true);
      data.putString("f16_decoy_source", targetId);
   }
}
