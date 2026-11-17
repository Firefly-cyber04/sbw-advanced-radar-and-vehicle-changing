package com.f16addon.mixin;

import com.atsuishio.superbwarfare.tools.ParticleTool;
import com.f16addon.ClientTuning;
import com.f16addon.core.ExplosionLod;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ParticleTool.class, remap = false)
public abstract class ExplosionParticleLodMixin {
    @Unique private static long f16addon$effectTick = Long.MIN_VALUE;
    @Unique private static int f16addon$detailed;
    @Unique private static boolean f16addon$replacement;

    @Inject(method = "spawnExplosionParticlesClient", at = @At("HEAD"), cancellable = true)
    private static void f16addon$limitEffects(ParticleTool.ParticleType type, Level level, Vec3 position, CallbackInfo ci) {
        if (f16addon$replacement || !ClientTuning.EXPLOSION_LOD.get()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || level == null) return;
        long now = level.getGameTime();
        if (f16addon$effectTick != now) { f16addon$effectTick = now; f16addon$detailed = 0; }
        int wanted = ExplosionLod.choose(type.ordinal(), mc.player.distanceToSqr(position), f16addon$detailed,
                ClientTuning.DETAILED_EXPLOSIONS_PER_TICK.get(), ClientTuning.FULL_EFFECT_RANGE.get(), ClientTuning.MEDIUM_EFFECT_RANGE.get());
        if (wanted > 1) f16addon$detailed++;
        if (wanted == type.ordinal()) return;
        f16addon$replacement = true;
        try { ParticleTool.spawnExplosionParticlesClient(ParticleTool.ParticleType.values()[wanted], level, position); }
        finally { f16addon$replacement = false; }
        ci.cancel();
    }
}
