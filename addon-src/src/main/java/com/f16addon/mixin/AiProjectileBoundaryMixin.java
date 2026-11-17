package com.f16addon.mixin;

import com.atsuishio.superbwarfare.entity.projectile.FastThrowableProjectile;
import com.f16addon.BattleBudget;
import com.f16addon.Config;
import com.f16addon.WorldQueries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stop own AI ammunition before its ray trace or movement reaches an unloaded chunk. */
@Mixin(targets = "com.atsuishio.superbwarfare.entity.projectile.FastThrowableProjectile", remap = false)
public abstract class AiProjectileBoundaryMixin {
    @Inject(method = {"tick()V", "m_8119_()V"}, at = @At("HEAD"), cancellable = true)
    private void f16addon$stayInLoadedBattlefield(CallbackInfo ci) {
        Entity self = (Entity)(Object)this;
        if (!self.getTags().contains(BattleBudget.AI_SHOT) || Config.AI_PROJECTILE_CHUNK_LOADING.get()
                || !(self.level() instanceof ServerLevel level)) return;
        if (!WorldQueries.isEntityTicking(level, self)
                || !WorldQueries.segmentLoaded(level, self.position(), self.position().add(self.getDeltaMovement()))) {
            self.discard(); ci.cancel();
        }
    }
    @Redirect(method = {"tick()V", "m_8119_()V", "updateManualTickRegistration()V"},
            at = @At(value = "INVOKE", target = "Lcom/atsuishio/superbwarfare/entity/projectile/FastThrowableProjectile;forceLoadChunk()Z"))
    private boolean f16addon$skipOwnChunkLoading(FastThrowableProjectile projectile) {
        if (projectile.getTags().contains(BattleBudget.AI_SHOT) && !Config.AI_PROJECTILE_CHUNK_LOADING.get()) return false;
        return projectile.forceLoadChunk();
    }

}
