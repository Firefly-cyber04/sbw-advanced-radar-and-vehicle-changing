package com.f16addon.client.map;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/** Duck interface implemented on {@code TacticalMapCache} by {@code TacticalMapCacheMixin}. */
public interface MapCacheAccess {
   /** Renders queued chunks, nearest to (x, z) first, until {@code budgetNanos} is spent (at least one). */
   int f16addon$drain(Level level, double x, double z, long budgetNanos);

   /** A chunk is unloading: take it out of the queue, rendering it first if {@code render}. */
   void f16addon$onChunkUnload(LevelChunk chunk, Level level, boolean render);
}
