package com.f16addon.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.f16addon.Config;
import com.f16addon.UnitTracker;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevent mob crews from adding native fire on top of manually spawned addon ammunition. */
@Mixin(value = VehicleEntity.class, remap = false)
public abstract class NativeWeaponGateMixin {
    @Inject(method = "canShoot(Lnet/minecraft/world/entity/LivingEntity;)Z", at = @At("HEAD"), cancellable = true)
    private void f16addon$singleWeaponController(LivingEntity gunner, CallbackInfoReturnable<Boolean> cir) {
        if (!(gunner instanceof Mob)) return;
        Entity self = (Entity)(Object)this;
        if (self.getControllingPassenger() instanceof Player) return;
        boolean addonAircraft = self.getTags().contains(UnitTracker.TAG_F16) || self.getTags().contains(UnitTracker.TAG_SAM);
        boolean orderedStop = self.getTags().contains(UnitTracker.TAG_GROUND) && "stop".equals(self.getPersistentData().getString("ground_mode"));
        boolean addonGround = self.getTags().contains(UnitTracker.TAG_GROUND) && !Config.GROUND_NATIVE_WEAPONS.get();
        if (addonAircraft || addonGround || orderedStop) cir.setReturnValue(false);
    }
}
