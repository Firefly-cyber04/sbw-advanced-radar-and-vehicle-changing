package com.f16addon;

import net.minecraftforge.common.ForgeConfigSpec;

/** Client-only visual settings. They change neither damage nor hit detection. */
public final class ClientTuning {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue EXPLOSION_LOD;
    public static final ForgeConfigSpec.DoubleValue FULL_EFFECT_RANGE;
    public static final ForgeConfigSpec.DoubleValue MEDIUM_EFFECT_RANGE;
    public static final ForgeConfigSpec.IntValue DETAILED_EXPLOSIONS_PER_TICK;
    static {
        var builder = new ForgeConfigSpec.Builder();
        builder.push("effects");
        EXPLOSION_LOD = builder.define("explosionLod", true);
        FULL_EFFECT_RANGE = builder.comment("Full effects within this distance.").defineInRange("fullEffectRange", 96.0, 16.0, 512.0);
        MEDIUM_EFFECT_RANGE = builder.comment("Small effects until this distance, mini flashes beyond it.").defineInRange("mediumEffectRange", 256.0, 32.0, 2048.0);
        DETAILED_EXPLOSIONS_PER_TICK = builder.comment("Additional explosions use small effects; audio and damage stay unchanged.").defineInRange("detailedExplosionsPerTick", 3, 0, 64);
        builder.pop(); SPEC = builder.build();
    }
    private ClientTuning() {}
}
