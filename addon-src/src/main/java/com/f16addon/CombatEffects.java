package com.f16addon;

import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = F16Addon.MODID)
public final class CombatEffects {
    private CombatEffects() {}

    @SubscribeEvent
    public static void onDetonate(ExplosionEvent.Detonate event) {
        if (event.getLevel().isClientSide || !Config.PRESERVE_TERRAIN.get()) return;
        Entity source = event.getExplosion().getDirectSourceEntity();
        if (source != null && source.getTags().contains(BattleBudget.AI_SHOT)) {
            // Keep the explosion's entity damage. This does not skip its earlier ray sampling.
            event.getAffectedBlocks().clear();
        }
    }
}
