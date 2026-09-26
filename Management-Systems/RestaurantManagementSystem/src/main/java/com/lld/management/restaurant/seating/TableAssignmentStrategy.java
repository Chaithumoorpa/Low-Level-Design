package com.lld.management.restaurant.seating;

import com.lld.management.restaurant.model.Table;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Picks one table from those that are big enough and free for the whole slot. The restaurant has
 * already filtered the candidates; the strategy only expresses a preference.
 */
public interface TableAssignmentStrategy {

    Optional<Table> choose(int partySize, List<Table> candidates);

    /** Smallest table that fits: keeps the 6-tops free for big parties (the usual default). */
    static TableAssignmentStrategy smallestFit() {
        return (party, candidates) -> candidates.stream()
                .min(Comparator.comparingInt(Table::seats).thenComparing(Table::id));
    }

    /** First table in floor-plan order, whatever its size (simple, but wastes big tables). */
    static TableAssignmentStrategy firstAvailable() {
        return (party, candidates) -> candidates.stream().min(Comparator.comparing(Table::id));
    }
}
