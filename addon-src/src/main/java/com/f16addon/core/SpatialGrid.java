package com.f16addon.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Horizontal broad phase only. Callers retain their exact 3D distance and hostility checks. */
public final class SpatialGrid<T> {
    private final double cellSize;
    private final Map<Long, List<T>> cells = new HashMap<>();

    public SpatialGrid(double cellSize) {
        if (!Double.isFinite(cellSize) || cellSize <= 0) throw new IllegalArgumentException("cellSize");
        this.cellSize = cellSize;
    }

    public void clear() { cells.clear(); }

    public void add(double x, double z, T value) {
        cells.computeIfAbsent(key(cell(x), cell(z)), ignored -> new ArrayList<>()).add(value);
    }

    public void forEachNear(double x, double z, double range, Consumer<T> action) {
        if (!Double.isFinite(x) || !Double.isFinite(z) || !Double.isFinite(range) || range < 0) return;
        int minX = cell(x - range), maxX = cell(x + range);
        int minZ = cell(z - range), maxZ = cell(z + range);
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                List<T> bucket = cells.get(key(cx, cz));
                if (bucket != null) bucket.forEach(action);
            }
        }
    }

    public List<T> near(double x, double z, double range) {
        List<T> result = new ArrayList<>();
        forEachNear(x, z, range, result::add);
        return result;
    }

    private int cell(double coordinate) { return (int) Math.floor(coordinate / cellSize); }
    private static long key(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
}
