package com.f16addon;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.event.TickEvent.ServerTickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

@EventBusSubscriber(
   modid = "f16addon"
)
public final class WaspEvents {
   private static final ResourceKey<CreativeModeTab> COMBAT_TAB = ResourceKey.create(Registries.CREATIVE_MODE_TAB, new ResourceLocation("combat"));
   private static final int SAM_SCAN_INTERVAL = 100;
   private static final Map<ResourceKey<Level>, List<Entity>> SAM_CACHE = new HashMap<>();
   private static final Map<ResourceKey<Level>, Long> SAM_LAST_SCAN = new HashMap<>();

   private WaspEvents() {
   }

   @SubscribeEvent
   public static void onServerTick(ServerTickEvent var0) {
      if (var0.phase == Phase.END) {
         MinecraftServer var1 = var0.getServer();
         if (var1 != null) {
            for (ServerLevel var3 : var1.getAllLevels()) {
               ResourceKey<Level> var4 = var3.dimension();
               long var5 = var3.getGameTime();
               Long var7 = SAM_LAST_SCAN.get(var4);
               List<Entity> var8 = SAM_CACHE.get(var4);
               if (var8 == null || var7 == null || var5 - var7 >= 100L) {
                  var8 = new ArrayList<>();

                  for (Entity var10 : var3.getAllEntities()) {
                     if (var10 != null && var10.getTags().contains("enemy_sam")) {
                        var8.add(var10);
                     }
                  }

                  SAM_CACHE.put(var4, var8);
                  SAM_LAST_SCAN.put(var4, var5);
               }

               for (Entity var12 : new ArrayList<>(var8)) {
                  if (var12.isAlive() && var12.level() == var3) {
                     SamUnitAI.tick(var3, var12);
                  }
               }
            }
         }
      }
   }

   @SubscribeEvent
   public static void addCreative(BuildCreativeModeTabContentsEvent var0) {
      if (var0.getTabKey() == COMBAT_TAB) {
         var0.accept(() -> (ItemLike)ModItems.ENEMY_F16_DEPLOYER.get());
         var0.accept(() -> (ItemLike)ModItems.ENEMY_BMPT72_DEPLOYER.get());
         var0.accept(() -> (ItemLike)ModItems.ENEMY_T80_DEPLOYER.get());
         var0.accept(() -> (ItemLike)ModItems.ENEMY_WASP_DEPLOYER.get());
         var0.accept(() -> (ItemLike)ModItems.GROUND_COMMAND_TABLET.get());
         var0.accept(() -> (ItemLike)ModItems.TARGETING_POD.get());
      }
   }
}
