package com.f16addon.mixin;

import com.f16addon.BattleBudget;
import com.f16addon.Config;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** SBW destroys blocks inside explodeBlocks(), before Forge's later Detonate event. */
@Mixin(targets = "com.atsuishio.superbwarfare.tools.CustomExplosion", remap = false)
public abstract class CustomExplosionMixin {
    @Inject(method = "explodeBlocks()V", at = @At("HEAD"), cancellable = true)
    private void f16addon$preserveTerrain(CallbackInfo ci) {
        if (!Config.PRESERVE_TERRAIN.get()) return;
        Entity source = ((Explosion)(Object)this).getDirectSourceEntity();
        if (source != null && source.getTags().contains(BattleBudget.AI_SHOT)) ci.cancel();
    }
}
