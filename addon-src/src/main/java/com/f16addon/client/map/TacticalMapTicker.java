package com.f16addon.client.map;

import com.atsuishio.superbwarfare.client.map.TacticalMapCache;
import com.atsuishio.superbwarfare.client.screens.TacticalMapScreen;
import com.f16addon.F16Addon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/**
 * Keeps Superb Warfare's tactical-map terrain cache current while the terminal is closed.
 *
 * <p>SBW queues every chunk the client loads (holding the whole {@code LevelChunk}) but only processes the queue
 * while the map screen is open. After a few minutes of flying that is thousands of retained chunks and a multi-second
 * freeze the next time the terminal opens. Here the queue is drained 1 ms per tick in the background, and a chunk
 * that unloads before it was drawn is drawn (or dropped) right away so the queue never holds dead chunks.
 */
@EventBusSubscriber(modid = F16Addon.MODID, value = Dist.CLIENT)
public final class TacticalMapTicker {
   private static final long BACKGROUND_BUDGET_NS = 1_000_000L;
   private static final int MAX_UNLOAD_RENDERS_PER_TICK = 24;
   private static int unloadRenders;

   private TacticalMapTicker() {
   }

   private static MapCacheAccess cache() {
      Object cache = TacticalMapCache.INSTANCE;
      return cache instanceof MapCacheAccess access ? access : null;
   }

   @SubscribeEvent
   public static void onClientTick(TickEvent.ClientTickEvent event) {
      if (event.phase != TickEvent.Phase.END) {
         return;
      }

      unloadRenders = 0;
      Minecraft mc = Minecraft.getInstance();
      if (mc.level == null || mc.player == null || mc.screen instanceof TacticalMapScreen) {
         return;
      }

      MapCacheAccess access = cache();
      if (access == null || TacticalMapCache.INSTANCE.getPendingUpdateCount() == 0) {
         return;
      }

      access.f16addon$drain(mc.level, mc.player.getX(), mc.player.getZ(), BACKGROUND_BUDGET_NS);
      TacticalMapCache.INSTANCE.flushPendingDiskWritesIfNeeded();
   }

   @SubscribeEvent
   public static void onChunkUnload(ChunkEvent.Unload event) {
      if (!(event.getLevel() instanceof ClientLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) {
         return;
      }

      MapCacheAccess access = cache();
      if (access != null) {
         access.f16addon$onChunkUnload(chunk, level, unloadRenders++ < MAX_UNLOAD_RENDERS_PER_TICK);
      }
   }
}
