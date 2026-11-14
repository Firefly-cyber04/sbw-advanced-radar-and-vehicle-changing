package com.f16addon.client.map;

import com.f16addon.F16Addon;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Writes tactical-map cache tiles off the render thread. File format is unchanged from Superb Warfare
 * ({@code <tileX>_<tileZ>.bin}: little-endian {@code int count, (long chunkKey, int len, byte[len] deflated)*}), so
 * SBW keeps reading the cache it wrote before. One daemon thread serialises writes to the same tile; files are
 * replaced with an atomic move so the render thread never reads a half-written tile.
 */
public final class MapDiskWriter {
   private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "EnemyForces-TacticalMapIO");
      t.setDaemon(true);
      t.setPriority(Thread.MIN_PRIORITY);
      return t;
   });

   private MapDiskWriter() {
   }

   /** {@code chunks} is keyed like SBW's {@code chunkPosKey}: {@code cx << 32 | cz & 0xFFFFFFFF}. */
   public static void submit(File dir, Map<Long, byte[]> chunks) {
      Map<Long, Map<Long, byte[]>> byTile = new LinkedHashMap<>();
      for (Map.Entry<Long, byte[]> e : chunks.entrySet()) {
         long key = e.getKey();
         int tileX = (int) (key >> 32) >> 4;
         int tileZ = (int) key >> 4;
         long tileKey = (long) tileX << 32 | (long) tileZ & 0xFFFFFFFFL;
         byTile.computeIfAbsent(tileKey, k -> new LinkedHashMap<>()).put(key, e.getValue());
      }

      IO.execute(() -> {
         for (Map.Entry<Long, Map<Long, byte[]>> tile : byTile.entrySet()) {
            long tileKey = tile.getKey();
            write(dir, (int) (tileKey >> 32), (int) tileKey, tile.getValue());
         }
      });
   }

   private static void write(File dir, int tileX, int tileZ, Map<Long, byte[]> chunks) {
      File file = new File(dir, tileX + "_" + tileZ + ".bin");
      try {
         Map<Long, byte[]> merged = read(file);
         merged.putAll(chunks);
         int size = 4;
         for (byte[] data : merged.values()) {
            size += 12 + data.length;
         }

         ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
         buf.putInt(merged.size());
         for (Map.Entry<Long, byte[]> e : merged.entrySet()) {
            buf.putLong(e.getKey());
            buf.putInt(e.getValue().length);
            buf.put(e.getValue());
         }

         dir.mkdirs();
         File tmp = new File(dir, file.getName() + ".tmp");
         Files.write(tmp.toPath(), buf.array());
         try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
         }
      } catch (IOException | RuntimeException e) {
         F16Addon.LOGGER.warn("[Enemy Forces] Failed to write tactical map tile {},{}: {}", tileX, tileZ, e.toString());
      }
   }

   private static Map<Long, byte[]> read(File file) throws IOException {
      Map<Long, byte[]> result = new HashMap<>();
      if (!file.isFile()) {
         return result;
      }

      ByteBuffer buf = ByteBuffer.wrap(Files.readAllBytes(file.toPath())).order(ByteOrder.LITTLE_ENDIAN);
      if (buf.remaining() < 4) {
         return result;
      }

      int count = buf.getInt();
      for (int i = 0; i < count && buf.remaining() >= 12; i++) {
         long key = buf.getLong();
         int len = buf.getInt();
         if (len < 0 || buf.remaining() < len) {
            break;
         }

         byte[] data = new byte[len];
         buf.get(data);
         result.put(key, data);
      }

      return result;
   }
}
