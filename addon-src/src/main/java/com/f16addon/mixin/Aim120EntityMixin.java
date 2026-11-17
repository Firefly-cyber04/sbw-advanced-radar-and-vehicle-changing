package com.f16addon.mixin;

import com.f16addon.MissileFlight;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** DragonRise AIM-120: measured target velocity for the intercept point; proximity fuze arms clear of the launcher. */
@Mixin(targets = "com.redabysslucia.dragonrise_reforge.entities.projectile.Aim120Entity", remap = false)
public abstract class Aim120EntityMixin {
   @Redirect(method = { "recalculateToVec()Lnet/minecraft/world/phys/Vec3;" }, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getDeltaMovement()Lnet/minecraft/world/phys/Vec3;"), require = 0)
private Vec3 f16addon$targetVelocity(Entity target) {
    return MissileFlight.velocity(target, (Entity) (Object) this);
}

   @Inject(method = { "isProximityTarget(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;)Z" }, at = { @At(value = "HEAD") }, cancellable = true, require = 0)
private void f16addon$fuzeArming(Entity candidate, Entity owner, Entity ownerVehicle, CallbackInfoReturnable<Boolean> cir) {
    if (!MissileFlight.armed((Entity) (Object) this)) {
        cir.setReturnValue(false);
    }
}

   @Redirect(method = { "recalculateToVec()Lnet/minecraft/world/phys/Vec3;" }, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;m_20184_()Lnet/minecraft/world/phys/Vec3;"), require = 0)
private Vec3 f16addon$targetVelocityProduction(Entity target) {
    return MissileFlight.velocity(target, (Entity) (Object) this);
}
}
