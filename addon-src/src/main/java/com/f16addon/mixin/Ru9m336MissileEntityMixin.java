package com.f16addon.mixin;

import com.atsuishio.superbwarfare.entity.projectile.Ru9m336MissileEntity;
import com.f16addon.MissileFlight;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 9M336: measured target velocity for the intercept point; SAM proximity fuze arms clear of the launcher. */
@Mixin(value = Ru9m336MissileEntity.class, remap = false)
public abstract class Ru9m336MissileEntityMixin {
   @Redirect(method = "m_8119_()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;m_20184_()Lnet/minecraft/world/phys/Vec3;"))
   private Vec3 f16addon$targetVelocity(Entity target) {
      return MissileFlight.velocity(target, (Entity) (Object) this);
   }

   @Inject(method = "samIsProximityTarget(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;)Z",
      at = @At("HEAD"), cancellable = true)
   private void f16addon$fuzeArming(Entity candidate, Entity owner, Entity ownerVehicle, CallbackInfoReturnable<Boolean> cir) {
      if (!MissileFlight.armed((Entity) (Object) this)) {
         cir.setReturnValue(false);
      }
   }
}
