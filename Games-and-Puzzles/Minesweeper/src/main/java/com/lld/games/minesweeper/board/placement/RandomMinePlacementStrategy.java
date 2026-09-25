package com.lld.games.minesweeper.board.placement;

import com.lld.games.minesweeper.model.Position;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Uniformly random mines using a partial Fisher-Yates shuffle over the allowed cells.
 *
 * <p>Why not "pick a random cell, retry if taken"? On a dense board (e.g. 99 mines in 480 cells,
 * or worse, custom boards that are 90% mines) rejection sampling slows down badly. The shuffle is
 * always O(rows * cols) and always terminates.
 */
public class RandomMinePlacementStrategy implements MinePlacementStrategy {

    private final Random random;

    public RandomMinePlacementStrategy() {
        this(new Random());
    }

    public RandomMinePlacementStrategy(long seed) {
        this(new Random(seed));
    }

    public RandomMinePlacementStrategy(Random random) {
        this.random = random;
    }

    @Override
    public Set<Position> placeMines(int rows, int cols, int mineCount, Set<Position> excluded) {
        List<Position> candidates = new ArrayList<>(rows * cols);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                Position p = new Position(r, c);
                if (!excluded.contains(p)) {
                    candidates.add(p);
                }
            }
        }
        if (mineCount > candidates.size()) {
            throw new IllegalArgumentException("Cannot place " + mineCount + " mines in "
                    + candidates.size() + " available cells");
        }

        // Partial Fisher-Yates: only the first mineCount slots need to be shuffled.
        for (int i = 0; i < mineCount; i++) {
            int j = i + random.nextInt(candidates.size() - i);
            Collections.swap(candidates, i, j);
        }
        return new HashSet<>(candidates.subList(0, mineCount));
    }
}
