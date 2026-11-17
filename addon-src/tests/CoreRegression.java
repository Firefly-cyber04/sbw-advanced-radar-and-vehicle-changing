import com.f16addon.core.*;
import com.f16addon.client.map.MapDiskWriter;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

public final class CoreRegression {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private record Point(int id, double x, double z) {}
    private static long key(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }

    public static void main(String[] args) throws Exception {
        check(TickMath.due(1000, Long.MIN_VALUE, 5), "first threat/HUD scan must run");
        check(!TickMath.due(1004, 1000, 5), "cached threat snapshot");
        check(TickMath.due(1005, 1000, 5), "snapshot expires on time");
        check(TickMath.due(10, 1000, 5), "world time rewind resets cache");
        check(TickMath.due(Long.MAX_VALUE, -5, 5), "overflow cannot freeze clock");
        check(TickMath.staggered(19, 1, 20), "entity phase offset");
        SpatialGrid<Point> grid = new SpatialGrid<>(128);
        Random random = new Random(1620);
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            Point p = new Point(i, random.nextDouble() * 8000 - 4000, random.nextDouble() * 8000 - 4000);
            points.add(p); grid.add(p.x, p.z, p);
        }
        for (int q = 0; q < 100; q++) {
            double x = random.nextDouble() * 6000 - 3000, z = random.nextDouble() * 6000 - 3000, radius = 10 + random.nextDouble() * 1024;
            Set<Integer> expected = new HashSet<>(), actual = new HashSet<>();
            for (Point p : points) if (Math.hypot(p.x - x, p.z - z) <= radius) expected.add(p.id);
            for (Point p : grid.near(x, z, radius)) if (Math.hypot(p.x - x, p.z - z) <= radius) actual.add(p.id);
            check(expected.equals(actual), "spatial grid must not miss threats, query " + q);
        }
        check(ThreatMath.interceptTime(100, 0, 0, 2, 0, 0, 8, 100) == 50, "incoming threat");
        check(ThreatMath.interceptTime(100, 0, 0, -2, 0, 0, 8, 100) < 0, "receding shell is not a threat");
        check(ThreatMath.interceptTime(100, 30, 0, 2, 0, 0, 8, 100) < 0, "nearby shell missing target");
        check(ThreatMath.interceptTime(500, 0, 0, 2, 0, 0, 8, 100) < 0, "prediction horizon");
        check(ThreatMath.burstCooldown(5, 6, 6, 40) == 6, "within burst");
        check(ThreatMath.burstCooldown(6, 6, 6, 40) == 40, "cooling pause");

        check(ExplosionLod.choose(6, 50 * 50, 0, 3, 96, 256) == 6, "nearby explosion keeps detail");
        check(ExplosionLod.choose(6, 150 * 150, 0, 3, 96, 256) == 1, "middle distance uses small effects");
        check(ExplosionLod.choose(6, 300 * 300, 0, 3, 96, 256) == 0, "far explosion keeps a mini flash");
        check(ExplosionLod.choose(6, 25, 3, 3, 96, 256) == 1, "dense explosions respect detail budget");
        check(ExplosionLod.choose(0, 25, 3, 3, 96, 256) == 0, "never increase small effects");

        Path root = Files.createTempDirectory("enemyforces-map-test-");
        try {
            Path worldA = root.resolve("world-a"), worldB = root.resolve("world-b");
            long first = key(0, 0), second = key(1, 0), negative = key(-1, -1);
            check(!MapDiskWriter.submit(worldA.toFile(), Map.of(first, new byte[]{1, 2}), 1), "reject over-budget batch atomically");
            check(MapDiskWriter.submit(worldA.toFile(), Map.of(first, new byte[]{1}, second, new byte[]{2})), "initial tile write");
            check(MapDiskWriter.awaitIdle(5000), "initial disk IO drains");
            for (int i = 0; i < 100; i++) check(MapDiskWriter.submit(worldA.toFile(), Map.of(first, new byte[]{(byte)i})), "coalesced update accepted");
            check(MapDiskWriter.submit(worldA.toFile(), Map.of(negative, new byte[]{42})), "negative coordinates");
            check(MapDiskWriter.submit(worldB.toFile(), Map.of(first, new byte[]{9})), "separate world");
            check(MapDiskWriter.awaitIdle(5000), "all map writes drain");
            Map<Long, byte[]> contents = read(worldA.resolve("0_0.bin"));
            check(contents.get(first)[0] == 99, "latest update wins");
            check(contents.get(second)[0] == 2, "unchanged chunks retained");
            check(read(worldA.resolve("-1_-1.bin")).get(negative)[0] == 42, "negative tile address");
            check(read(worldB.resolve("0_0.bin")).get(first)[0] == 9, "world caches stay isolated");
            check(MapDiskWriter.pendingBytes() == 0, "pending byte accounting returns to zero");
            check(!MapDiskWriter.submit(worldA.toFile(), Map.of(first, new byte[65537])), "reject oversized chunk payload");
        } finally {
            try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
        System.out.println("PASS: " + checks + " core and map IO regression checks");
    }

    private static Map<Long, byte[]> read(Path path) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN);
        int count = buffer.getInt(); Map<Long, byte[]> result = new HashMap<>();
        for (int i = 0; i < count; i++) { long key = buffer.getLong(); byte[] payload = new byte[buffer.getInt()]; buffer.get(payload); result.put(key, payload); }
        return result;
    }
}
