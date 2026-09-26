package com.lld.booking.ride.geo;

import com.lld.booking.ride.model.Location;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Spatial index: the map is cut into square cells; each cell lists the ids located in it. A "nearby"
 * search looks at the cell of the point and then rings of cells around it, instead of checking every
 * driver in the city. (Geohash, S2 or H3 cells play this role in production.)
 *
 * <p>Not thread-safe; the ride service calls it under its lock.
 */
public final class GridIndex {

    private record Cell(long cx, long cy) {
    }

    private final double cellKm;
    private final Map<Cell, Set<String>> cells = new HashMap<>();
    private final Map<String, Location> where = new HashMap<>();

    public GridIndex(double cellKm) {
        if (cellKm <= 0) {
            throw new IllegalArgumentException("Cell size must be positive");
        }
        this.cellKm = cellKm;
    }

    public void put(String id, Location at) {
        remove(id);
        where.put(id, at);
        cells.computeIfAbsent(cellOf(at), c -> new HashSet<>()).add(id);
    }

    public void remove(String id) {
        Location old = where.remove(id);
        if (old != null) {
            Set<String> set = cells.get(cellOf(old));
            set.remove(id);
            if (set.isEmpty()) {
                cells.remove(cellOf(old));
            }
        }
    }

    public int size() {
        return where.size();
    }

    /**
     * Ids within {@code radiusKm} of {@code center} that pass {@code filter}, nearest first (ties by id),
     * at most {@code limit}. Only the (2r+1)² cells that can contain such points are scanned.
     */
    public List<String> nearby(Location center, double radiusKm, Predicate<String> filter, int limit) {
        Cell c = cellOf(center);
        long rings = (long) Math.ceil(radiusKm / cellKm);
        List<String> found = new ArrayList<>();
        for (long dx = -rings; dx <= rings; dx++) {
            for (long dy = -rings; dy <= rings; dy++) {
                for (String id : cells.getOrDefault(new Cell(c.cx() + dx, c.cy() + dy), Set.of())) {
                    if (where.get(id).distanceTo(center) <= radiusKm && filter.test(id)) {
                        found.add(id);
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble((String id) -> where.get(id).distanceTo(center)).thenComparing(id -> id));
        return found.size() > limit ? found.subList(0, limit) : found;
    }

    private Cell cellOf(Location l) {
        return new Cell((long) Math.floor(l.x() / cellKm), (long) Math.floor(l.y() / cellKm));
    }
}
