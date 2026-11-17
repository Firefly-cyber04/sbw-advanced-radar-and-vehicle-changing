package com.f16addon.client.map;

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
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes tactical-map cache tiles off the render thread. File format is unchanged from Superb Warfare
 * ({@code <tileX>_<tileZ>.bin}: little-endian {@code int count, (long chunkKey, int len, byte[len] deflated)*}), so
 * SBW keeps reading the cache it wrote before. One daemon thread serialises writes to the same tile; files are
 * replaced with an atomic move so the render thread never reads a half-written tile.
 */
public final class MapDiskWriter {
   private static final ExecutorService IO = Executors.newSingleThreadExecutor( r -> {
       Thread thread = new Thread(r, "EnemyForces-TacticalMapIO");
       thread.setDaemon(true);
       thread.setPriority(Thread.MIN_PRIORITY);
       return thread;
   });

   private MapDiskWriter() {
   }


   public static boolean submit(File dir, Map<Long, byte[]> chunks) {
       return submit(dir, chunks, 32L * 1024 * 1024);
   }


   private static final Logger LOGGER = LoggerFactory.getLogger(MapDiskWriter.class);

   private static final Object LOCK = new Object();

   private static final Map<Tile, Map<Long, byte[]>> PENDING = new LinkedHashMap<>();

   private static final int MAX_PAYLOAD = 65_536;

   private static final int MAX_TILE_BYTES = 16 * 1024 * 1024 + 4096;

   private static long pendingBytes;

   private static int pendingChunks;

   private static boolean running;

   public static boolean submit(File dir, Map<Long, byte[]> chunks, long maximumBytes) {
       if (dir == null)
           return false;
       if (chunks.isEmpty())
           return true;
       Path root = dir.toPath().toAbsolutePath().normalize();
       synchronized (LOCK) {
           long extra = 0;
           int extraChunks = 0;
           for (Map.Entry<Long, byte[]> e : chunks.entrySet()) {
               byte[] payload = e.getValue();
               if (payload == null || payload.length > MAX_PAYLOAD)
                   return false;
               Map<Long, byte[]> tile = PENDING.get(tile(root, e.getKey()));
               byte[] previous = tile == null ? null : tile.get(e.getKey());
               if (previous == null)
                   extraChunks++;
               extra += payload.length - (previous == null ? 0 : previous.length);
           }
           if (pendingBytes + extra > Math.max(1, maximumBytes) || pendingChunks + extraChunks > 8192)
               return false;
           for (Map.Entry<Long, byte[]> e : chunks.entrySet()) {
               PENDING.computeIfAbsent(tile(root, e.getKey()),  ignored -> new LinkedHashMap<>()).put(e.getKey(), e.getValue().clone());
           }
           pendingBytes += extra;
           pendingChunks += extraChunks;
           if (!running) {
               running = true;
               IO.execute(MapDiskWriter::drain);
           }
           return true;
       }
   }

   private static Tile tile(Path dir, long chunk) {
       return new Tile(dir, ((int) (chunk >> 32)) >> 4, ((int) chunk) >> 4);
   }

   private static void drain() {
       while (true) {
           Tile tile;
           Map<Long, byte[]> chunks;
           synchronized (LOCK) {
               var iterator = PENDING.entrySet().iterator();
               if (!iterator.hasNext()) {
                   running = false;
                   LOCK.notifyAll();
                   return;
               }
               var entry = iterator.next();
               tile = entry.getKey();
               chunks = entry.getValue();
               iterator.remove();
               for (byte[] payload : chunks.values()) pendingBytes -= payload.length;
               pendingChunks -= chunks.size();
           }
           try {
               write(tile, chunks);
           } catch (IOException | RuntimeException first) {
               try {
                   write(tile, chunks);
               } catch (IOException | RuntimeException second) {
                   LOGGER.warn("[Enemy Forces] Could not save map tile {},{}: {}", tile.x, tile.z, second.toString());
               }
           }
       }
   }

   private static void write(Tile tile, Map<Long, byte[]> chunks) throws IOException {
       Path file = tile.directory.resolve(tile.x + "_" + tile.z + ".bin");
       Map<Long, byte[]> merged = read(file);
       merged.putAll(chunks);
       int length = 4;
       for (byte[] payload : merged.values()) length = Math.addExact(length, 12 + payload.length);
       if (length > MAX_TILE_BYTES)
           throw new IOException("Oversized tactical map tile");
       ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
       buffer.putInt(merged.size());
       for (var entry : merged.entrySet()) buffer.putLong(entry.getKey()).putInt(entry.getValue().length).put(entry.getValue());
       Files.createDirectories(tile.directory);
       Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
       Files.write(temporary, buffer.array());
       try {
           Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
       } catch (AtomicMoveNotSupportedException unsupported) {
           Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
       }
   }

   private static Map<Long, byte[]> read(Path file) throws IOException {
       Map<Long, byte[]> result = new HashMap<>();
       if (!Files.isRegularFile(file))
           return result;
       long size = Files.size(file);
       if (size < 4 || size > MAX_TILE_BYTES)
           throw new IOException("Invalid tactical map tile length");
       ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
       int count = buffer.getInt();
       if (count < 0 || count > 256)
           throw new IOException("Invalid tactical map chunk count");
       for (int i = 0; i < count; i++) {
           if (buffer.remaining() < 12)
               throw new IOException("Truncated tactical map tile");
           long key = buffer.getLong();
           int length = buffer.getInt();
           if (length < 0 || length > MAX_PAYLOAD || length > buffer.remaining())
               throw new IOException("Invalid tactical map chunk length");
           byte[] payload = new byte[length];
           buffer.get(payload);
           result.put(key, payload);
       }
       return result;
   }

   public static long pendingBytes() {
       synchronized (LOCK) {
           return pendingBytes;
       }
   }

   public static boolean awaitIdle(long timeoutMillis) throws InterruptedException {
       long deadline = System.nanoTime() + timeoutMillis * 1_000_000;
       synchronized (LOCK) {
           while (running) {
               long remaining = deadline - System.nanoTime();
               if (remaining <= 0)
                   return false;
               LOCK.wait(Math.max(1, remaining / 1_000_000));
           }
           return true;
       }
   }

   private record Tile(Path directory, int x, int z) {
   }
}
