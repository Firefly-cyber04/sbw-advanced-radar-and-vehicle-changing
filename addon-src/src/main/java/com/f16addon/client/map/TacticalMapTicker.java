package com.f16addon.client.map;

import com.atsuishio.superbwarfare.client.map.TacticalMapCache;
import com.atsuishio.superbwarfare.client.screens.TacticalMapScreen;
import com.f16addon.client.map.MapCacheAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid="f16addon", value={Dist.CLIENT})
public final class TacticalMapTicker {
    private static final long BACKGROUND_BUDGET_NS = 1000000L;
    private static final int MAX_UNLOAD_RENDERS_PER_TICK = 4;
    private static int unloadRenders;
    private static long unloadNanos;

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
        unloadRenders = 0; unloadNanos = 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen instanceof TacticalMapScreen) {
            return;
        }
        MapCacheAccess access = TacticalMapTicker.cache();
        if (access == null) {
            return;
        }
        if (TacticalMapCache.INSTANCE.getPendingUpdateCount() > 0)
            access.f16addon$drain((Level)mc.level, mc.player.getX(), mc.player.getZ(), BACKGROUND_BUDGET_NS);
        TacticalMapCache.INSTANCE.flushPendingDiskWritesIfNeeded();
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ClientLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) return;
        MapCacheAccess access = TacticalMapTicker.cache();
        if (access != null) {
            boolean render = unloadRenders < MAX_UNLOAD_RENDERS_PER_TICK && unloadNanos < 500_000L;
            long start = System.nanoTime();
            access.f16addon$onChunkUnload(chunk, level, render);
            if (render) { unloadRenders++; unloadNanos += System.nanoTime() - start; }
        }
    }
}
