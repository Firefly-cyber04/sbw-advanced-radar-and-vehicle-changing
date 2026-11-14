package com.f16addon;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/**
 * Creative tab entries. SAM ticking moved into {@link F16Addon}'s single tick loop (1.0.0 ran a second full-entity
 * scan here every 100 ticks and kept removed entities in a stale cache in between).
 */
@EventBusSubscriber(modid = F16Addon.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class WaspEvents {
   private static final ResourceKey<CreativeModeTab> COMBAT_TAB = ResourceKey.create(Registries.CREATIVE_MODE_TAB, ResourceLocation.tryParse("minecraft:combat"));

   private WaspEvents() {
   }

   @SubscribeEvent
   public static void addCreative(BuildCreativeModeTabContentsEvent event) {
      if (event.getTabKey() == COMBAT_TAB) {
         event.accept(ModItems.ENEMY_F16_DEPLOYER);
         event.accept(ModItems.ENEMY_BMPT72_DEPLOYER);
         event.accept(ModItems.ENEMY_T80_DEPLOYER);
         event.accept(ModItems.ENEMY_WASP_DEPLOYER);
         event.accept(ModItems.GROUND_COMMAND_TABLET);
         event.accept(ModItems.TARGETING_POD);
      }
   }
}
