package com.f16addon.mixin;

import com.atsuishio.superbwarfare.entity.projectile.MissileProjectile;
import com.atsuishio.superbwarfare.init.ModTags;
import com.atsuishio.superbwarfare.tools.SeekTool;
import com.f16addon.MissileFlight;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MissileProjectile.class, remap = false)
public abstract class MissileProjectileMixin {
   private static final double DECOY_RANGE = 32.0;
   private static final double DECOY_ANGLE = 60.0;
   private static final double DECOY_CHANCE = 0.25;

   /**
    * Same rule as SBW (each decoy within 32 blocks, 60° of the seeker axis, visible and not friendly: 25 % per tick),
    * but SBW's {@code SeekTool.seekLivingEntities} walked every entity in the level — on both sides, every tick, for
    * every missile — and only then kept the decoys. This asks for decoys around the missile.
    */
   @Inject(method = "distractedByDecoy()V", at = @At("HEAD"), cancellable = true)
   private void f16addon$decoysNearby(CallbackInfo ci) {
      ci.cancel();
      MissileProjectile self = (MissileProjectile) (Object) this;
      if (self.isDistracted()) {
         return;
      }

      Vec3 eye = self.getEyePosition();
      List<Entity> decoys = self.level().getEntities(self, self.getBoundingBox().inflate(DECOY_RANGE + 2.0),
         e -> e.getType().is(ModTags.EntityTypes.DECOY));
      for (Entity d : decoys) {
         if (d.position().distanceToSqr(eye) > DECOY_RANGE * DECOY_RANGE || !(SeekTool.calculateAngle(d, self) < DECOY_ANGLE)) {
            continue;
         }

         if (!SeekTool.BASIC_FILTER.test(d) || !SeekTool.NOT_IN_SMOKE.test(d) || d.getVehicle() != null || SeekTool.IS_FRIENDLY.test(self, d)) {
            continue;
         }

         if (self.level().clip(new ClipContext(eye, d.getEyePosition(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self)).getType()
            == HitResult.Type.BLOCK) {
            continue;
         }

         if (Math.random() < DECOY_CHANCE) {
            self.setTargetUUID(d.getStringUUID());
            self.setDistracted(true);
            return;
         }
      }
   }

   @Inject(method = "missileIsProximityTarget(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;)Z",
      at = @At("HEAD"), cancellable = true)
   private void f16addon$fuzeArming(Entity candidate, Entity owner, Entity ownerVehicle, CallbackInfoReturnable<Boolean> cir) {
      if (!MissileFlight.armed((Entity) (Object) this)) {
         cir.setReturnValue(false);
      }
   }
}
