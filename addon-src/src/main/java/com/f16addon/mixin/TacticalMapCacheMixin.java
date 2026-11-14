package com.f16addon.mixin;

import com.atsuishio.superbwarfare.client.map.TacticalMapCache;
import com.f16addon.client.map.MapCacheAccess;
import com.f16addon.client.map.MapDiskWriter;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tactical map terrain cache: the main source of the terminal's stutter.
 *
 * <ul>
 *    <li>{@code periodicRescan} re-queued up to 512 <i>already drawn</i> chunks every 250 ms and
 *        {@code processChunkUpdates} redrew up to 256 chunks per tick; every redraw rewrote all 256 pixels, allocated
 *        two keys per pixel, deflated the chunk for disk, re-uploaded the 256×256 tile texture and threw away every LOD
 *        texture covering it (rebuilt on the next frame when zoomed out). Now a redraw that changes nothing costs only
 *        the block walk, the refresh wave is 64 chunks, and the queue is drained under a time budget.</li>
 *    <li>Chunks queued while the terminal is closed were kept (with their whole {@code LevelChunk}) until the
 *        terminal was next opened, then processed 256 per tick — a multi-second freeze after a long flight, and
 *        unbounded memory. {@code TacticalMapTicker} now drains the queue in the background and on chunk unload.</li>
 *    <li>Disk flushes (every 25 s and on close) read + rewrote tile files on the render thread; now asynchronous.</li>
 *    <li>LOD textures are invalidated at most every 750 ms.</li>
 * </ul>
 */
@Mixin(value = TacticalMapCache.class, remap = false)
public abstract class TacticalMapCacheMixin implements MapCacheAccess {
   @Unique
   private static final long f16addon$SCREEN_BUDGET_NS = 3_000_000L;
   @Unique
   private static final int f16addon$MAX_PER_CALL = 256;
   @Unique
   private static final int f16addon$REFRESH_WAVE = 64;
   @Unique
   private static final int f16addon$PENDING_PER_TICK = 64;
   @Unique
   private static final long f16addon$LOD_INTERVAL_MS = 750L;
   @Unique
   private static long f16addon$lastLodRebuild;
   @Unique
   private static String f16addon$worldId;
   @Unique
   private static Object f16addon$worldKey;
   @Unique
   private static long f16addon$worldIdAt;
   @Unique
   private static final int[] f16addon$colors = new int[256];

   @Shadow
   @Final
   private static ConcurrentHashMap<Long, LevelChunk> chunkUpdateQueue;
   @Shadow
   @Final
   private static Set<Long> drawnChunks;
   @Shadow
   @Final
   private static Set<TacticalMapCache.RegionPos> dirtyTiles;
   @Shadow
   @Final
   private static Set<TacticalMapCache.RegionPos> lodDirtyBaseTiles;
   @Shadow
   @Final
   private static ConcurrentHashMap<Long, short[]> chunkHeights;
   @Shadow
   @Final
   private static ConcurrentHashMap<TacticalMapCache.RegionPos, NativeImage> tileImages;
   @Shadow
   @Final
   private static ConcurrentHashMap<Long, byte[]> pendingDiskChunks;

   @Shadow
   private void ensureInit(Level level) {
      throw new AssertionError();
   }

   @Shadow
   private NativeImage getOrCreateTile(int rx, int rz) {
      throw new AssertionError();
   }

   @Shadow
   private int calculateABGR(MapColor color, MapColor.Brightness brightness) {
      throw new AssertionError();
   }

   @Shadow
   private MapColor.Brightness computeBrightness(int currentY, int prevY, int worldX, int worldZ) {
      throw new AssertionError();
   }

   @Shadow
   private void invalidateLodTilesForBaseTile(int rx, int rz) {
      throw new AssertionError();
   }

   @Shadow
   private void saveChunkToDisk(int cx, int cz) {
      throw new AssertionError();
   }

   @Shadow
   private File getCacheDir() {
      throw new AssertionError();
   }

   @Unique
   private static long f16addon$key(int cx, int cz) {
      return (long) cx << 32 | (long) cz & 0xFFFFFFFFL;
   }

   // ----------------------------------------------------------------------------------------------------------------
   // Queue processing
   // ----------------------------------------------------------------------------------------------------------------

   @Inject(method = "processChunkUpdates", at = @At("HEAD"), cancellable = true)
   private void f16addon$processChunkUpdates(Level level, double playerX, double playerZ, CallbackInfo ci) {
      ci.cancel();
      this.f16addon$drain(level, playerX, playerZ, f16addon$SCREEN_BUDGET_NS);
   }

   @Override
   public int f16addon$drain(Level level, double x, double z, long budgetNanos) {
      if (!(level instanceof ClientLevel) || chunkUpdateQueue.isEmpty()) {
         return 0;
      }

      this.ensureInit(level);
      long start = System.nanoTime();
      int pcx = Mth.floor(x) >> 4;
      int pcz = Mth.floor(z) >> 4;
      // K nearest without sorting the whole queue (it can hold thousands of entries after a long flight).
      int k = Math.min(f16addon$MAX_PER_CALL, chunkUpdateQueue.size());
      PriorityQueue<long[]> farthestFirst = new PriorityQueue<>(k + 1, (a, b) -> Long.compare(b[1], a[1]));
      for (Long boxed : chunkUpdateQueue.keySet()) {
         long key = boxed;
         long dx = (int) (key >> 32) - pcx;
         long dz = (int) key - pcz;
         long d = dx * dx + dz * dz;
         if (farthestFirst.size() < k) {
            farthestFirst.add(new long[]{key, d});
         } else if (d < farthestFirst.peek()[1]) {
            farthestFirst.poll();
            farthestFirst.add(new long[]{key, d});
         }
      }

      long[][] nearest = farthestFirst.toArray(new long[0][]);
      Arrays.sort(nearest, (a, b) -> Long.compare(a[1], b[1]));
      int done = 0;
      for (long[] entry : nearest) {
         if (done > 0 && System.nanoTime() - start > budgetNanos) {
            break;
         }

         LevelChunk chunk = chunkUpdateQueue.remove(entry[0]);
         if (chunk == null) {
            continue;
         }

         this.f16addon$render(chunk, level);
         drawnChunks.add(entry[0]);
         done++;
      }

      return done;
   }

   @Override
   public void f16addon$onChunkUnload(LevelChunk chunk, Level level, boolean render) {
      ChunkPos pos = chunk.getPos();
      long key = f16addon$key(pos.x, pos.z);
      LevelChunk queued = chunkUpdateQueue.remove(key);
      if (queued != null && render) {
         this.f16addon$render(queued, level);
         drawnChunks.add(key);
      }
   }

   @Inject(method = "updateChunk", at = @At("HEAD"), cancellable = true)
   private void f16addon$updateChunk(LevelChunk chunk, Level level, CallbackInfo ci) {
      ci.cancel();
      this.ensureInit(level);
      this.f16addon$render(chunk, level);
   }

   /**
    * Same colors / shading as SBW's {@code updateChunk} (top non-NONE map color, water / lava for fluids, slope
    * shading from the previous column), but only pixels that actually changed are written, the tile is marked dirty
    * once instead of once per pixel, and an unchanged chunk is neither re-uploaded, LOD-invalidated nor re-saved.
    */
   @Unique
   private void f16addon$render(LevelChunk chunk, Level level) {
      ChunkPos cp = chunk.getPos();
      int minX = cp.getMinBlockX();
      int minZ = cp.getMinBlockZ();
      int minBuild = level.getMinBuildHeight();
      int[] colors = f16addon$colors;
      short[] heights = new short[256];
      BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
      for (int z = 0; z < 16; z++) {
         int prevY = 0;
         boolean hasPrev = false;
         for (int x = 0; x < 16; x++) {
            int i = z * 16 + x;
            colors[i] = 0;
            int worldX = minX + x;
            int worldZ = minZ + z;
            int surfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            heights[i] = (short) surfaceY;
            if (surfaceY < minBuild) {
               prevY = minBuild;
               hasPrev = true;
               continue;
            }

            int y = surfaceY;
            pos.set(worldX, y, worldZ);
            BlockState state = chunk.getBlockState(pos);
            while (y > minBuild && state.getMapColor(level, pos) == MapColor.NONE) {
               pos.setY(--y);
               state = chunk.getBlockState(pos);
            }

            MapColor mapColor = state.getMapColor(level, pos);
            if (mapColor == MapColor.NONE) {
               prevY = y;
               hasPrev = true;
               continue;
            }

            MapColor actual = mapColor;
            int actualY = y;
            if (!state.getFluidState().isEmpty()) {
               actual = state.getFluidState().is(FluidTags.LAVA) ? MapColor.COLOR_ORANGE : MapColor.WATER;
               actualY = surfaceY;
            }

            MapColor.Brightness brightness = hasPrev ? this.computeBrightness(actualY, prevY, worldX, worldZ) : MapColor.Brightness.NORMAL;
            prevY = actualY;
            hasPrev = true;
            if (actual != MapColor.NONE) {
               colors[i] = this.calculateABGR(actual, brightness);
            }
         }
      }

      int tileRX = minX >> 8;
      int tileRZ = minZ >> 8;
      int tileX = minX & 255;
      int tileZ = minZ & 255;
      NativeImage tile = tileImages.get(new TacticalMapCache.RegionPos(tileRX, tileRZ));
      boolean pixelsChanged = false;
      for (int i = 0; i < 256; i++) {
         int c = colors[i];
         if (c == 0) {
            continue;
         }

         if (tile == null) {
            tile = this.getOrCreateTile(tileRX, tileRZ);
         }

         int px = tileX + (i & 15);
         int pz = tileZ + (i >> 4);
         if (tile.getPixelRGBA(px, pz) != c) {
            tile.setPixelRGBA(px, pz, c);
            pixelsChanged = true;
         }
      }

      if (pixelsChanged) {
         dirtyTiles.add(new TacticalMapCache.RegionPos(tileRX, tileRZ));
         this.invalidateLodTilesForBaseTile(tileRX, tileRZ);
      }

      long key = f16addon$key(cp.x, cp.z);
      short[] old = chunkHeights.get(key);
      boolean heightsChanged = old == null || !Arrays.equals(old, heights);
      if (heightsChanged) {
         chunkHeights.put(key, heights);
      }

      if (pixelsChanged || heightsChanged) {
         this.saveChunkToDisk(cp.x, cp.z);
      }
   }

   // ----------------------------------------------------------------------------------------------------------------
   // Throttles
   // ----------------------------------------------------------------------------------------------------------------

   /** Refresh wave of already drawn chunks: 512 → 64 per 250 ms (a 25×25-chunk view still cycles in ~2.5 s). */
   @ModifyConstant(method = "periodicRescan", constant = @Constant(intValue = 512))
   private int f16addon$refreshWave(int original) {
      return f16addon$REFRESH_WAVE;
   }

   /** Chunks restored from the disk cache per tick: the screen asks for 512 (×4 with a large backlog). */
   @ModifyVariable(method = "processPendingChunks", at = @At("HEAD"), argsOnly = true, ordinal = 0)
   private int f16addon$pendingPerTick(int maxCount) {
      return Math.min(maxCount, f16addon$PENDING_PER_TICK);
   }

   @Inject(method = "processDirtyLodTiles", at = @At("HEAD"), cancellable = true)
   private void f16addon$throttleLod(CallbackInfo ci) {
      if (lodDirtyBaseTiles.isEmpty()) {
         return;
      }

      long now = System.currentTimeMillis();
      if (now - f16addon$lastLodRebuild < f16addon$LOD_INTERVAL_MS && now >= f16addon$lastLodRebuild) {
         ci.cancel();
         return;
      }

      f16addon$lastLodRebuild = now;
   }

   /** SHA-256 of the server address (or level name + seed) was recomputed on every call, i.e. per chunk. */
   @Inject(method = "getWorldIdentifier", at = @At("HEAD"), cancellable = true)
   private void f16addon$cachedWorldId(CallbackInfoReturnable<String> cir) {
      Object key = f16addon$currentWorldKey();
      long now = System.currentTimeMillis();
      if (f16addon$worldId != null && key == f16addon$worldKey && now - f16addon$worldIdAt < 1000L && now >= f16addon$worldIdAt) {
         cir.setReturnValue(f16addon$worldId);
      }
   }

   @Inject(method = "getWorldIdentifier", at = @At("RETURN"))
   private void f16addon$storeWorldId(CallbackInfoReturnable<String> cir) {
      f16addon$worldId = cir.getReturnValue();
      f16addon$worldKey = f16addon$currentWorldKey();
      f16addon$worldIdAt = System.currentTimeMillis();
   }

   @Unique
   private static Object f16addon$currentWorldKey() {
      Minecraft mc = Minecraft.getInstance();
      Object server = mc.getSingleplayerServer();
      return server != null ? server : mc.getCurrentServer();
   }

   // ----------------------------------------------------------------------------------------------------------------
   // Disk
   // ----------------------------------------------------------------------------------------------------------------

   @Inject(method = "flushPendingDiskWrites", at = @At("HEAD"), cancellable = true)
   private void f16addon$flushAsync(CallbackInfo ci) {
      ci.cancel();
      if (pendingDiskChunks.isEmpty()) {
         return;
      }

      File dir = this.getCacheDir();
      Map<Long, byte[]> snapshot = new HashMap<>(pendingDiskChunks);
      pendingDiskChunks.clear();
      if (dir != null) {
         MapDiskWriter.submit(dir, snapshot);
      }
   }
}
