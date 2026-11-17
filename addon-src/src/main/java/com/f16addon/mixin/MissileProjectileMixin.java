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


   @Inject(method = { "distractedByDecoy()V" }, at = { @At(value = "HEAD") }, cancellable = true)
private void f16addon$decoysNearby(CallbackInfo ci) {
    ci.cancel();
    MissileProjectile self = (MissileProjectile) (Object) this;
    if (self.isDistracted()) {
        return;
    }
    Vec3 eye = self.getEyePosition();
    List<Entity> decoys = self.level().getEntities((Entity) self, self.getBoundingBox().inflate(34.0),  e -> e.getType().is(ModTags.EntityTypes.DECOY));
    for (Entity d : decoys) {
        if (d.position().distanceToSqr(eye) > 1024.0 || !(SeekTool.calculateAngle((Entity) d, (Entity) self) < 60.0) || !SeekTool.BASIC_FILTER.test(d) || !SeekTool.NOT_IN_SMOKE.test(d) || d.getVehicle() != null || SeekTool.IS_FRIENDLY.test(self, d) || self.level().clip(new ClipContext(eye, d.getEyePosition(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, (Entity) self)).getType() == HitResult.Type.BLOCK || !(Math.random() < 0.25))
            continue;
        self.setTargetUUID(d.getStringUUID());
        self.setDistracted(true);
        return;
    }
}

   @Inject(method = { "missileIsProximityTarget(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;)Z" }, at = { @At(value = "HEAD") }, cancellable = true, require = 0)
private void f16addon$fuzeArming(Entity candidate, Entity owner, Entity ownerVehicle, CallbackInfoReturnable<Boolean> cir) {
    if (!MissileFlight.armed((Entity) (Object) this)) {
        cir.setReturnValue(false);
    }
}

   @Inject(method = "getNoHitTicks()I", at = @At("HEAD"), cancellable = true)
private void f16addon$safeLaunch(CallbackInfoReturnable<Integer> cir) {
    Entity self = (Entity) (Object) this;
    if (!MissileFlight.armed(self))
        cir.setReturnValue(self.tickCount + 1);
}

   @Inject(method = "forceLoadChunk()Z", at = @At("HEAD"), cancellable = true)
private void f16addon$noAiChunkTickets(CallbackInfoReturnable<Boolean> cir) {
    Entity self = (Entity) (Object) this;
    if (self.getTags().contains(com.f16addon.BattleBudget.AI_SHOT) && !com.f16addon.Config.AI_PROJECTILE_CHUNK_LOADING.get())
        cir.setReturnValue(false);
}
}
