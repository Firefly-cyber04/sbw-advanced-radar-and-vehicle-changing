package com.f16addon.mixin;

import com.atsuishio.superbwarfare.entity.projectile.Agm65Entity;
import com.f16addon.MissileFlight;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** AGM-65 / Kh-39 (and DragonRise's AIM-120 base): intercept point uses the measured target velocity. */
@Mixin(value = Agm65Entity.class, remap = false)
public abstract class Agm65EntityMixin {
   @Redirect(method = { "tick()V", "m_8119_()V" }, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getDeltaMovement()Lnet/minecraft/world/phys/Vec3;"), require = 0)
private Vec3 f16addon$targetVelocity(Entity target) {
    return MissileFlight.velocity(target, (Entity) (Object) this);
}

   @Redirect(method = { "tick()V", "m_8119_()V" }, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;m_20184_()Lnet/minecraft/world/phys/Vec3;"), require = 0)
private Vec3 f16addon$targetVelocityProduction(Entity target) {
    return MissileFlight.velocity(target, (Entity) (Object) this);
}
}
