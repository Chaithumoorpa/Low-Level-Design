package com.lld.games.snakeandladder.board.placement;

import com.lld.games.snakeandladder.exception.InvalidBoardException;
import com.lld.games.snakeandladder.model.BoardEntity;
import com.lld.games.snakeandladder.model.Ladder;
import com.lld.games.snakeandladder.model.Snake;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Places the requested number of snakes and ladders at random, respecting every Board invariant:
 * starts are unique, never on cell 1 or the last cell, and no entity starts where another ends.
 * Pass a seed for reproducible boards (handy in tests and replays).
 */
public class RandomPlacementStrategy implements PlacementStrategy {

    private static final int MAX_ATTEMPTS_PER_ENTITY = 1_000;

    private final int snakeCount;
    private final int ladderCount;
    private final Random random;

    public RandomPlacementStrategy(int snakeCount, int ladderCount) {
        this(snakeCount, ladderCount, new Random());
    }

    public RandomPlacementStrategy(int snakeCount, int ladderCount, long seed) {
        this(snakeCount, ladderCount, new Random(seed));
    }

    private RandomPlacementStrategy(int snakeCount, int ladderCount, Random random) {
        if (snakeCount < 0 || ladderCount < 0) {
            throw new IllegalArgumentException("Counts must be non-negative");
        }
        this.snakeCount = snakeCount;
        this.ladderCount = ladderCount;
        this.random = random;
    }

    @Override
    public List<BoardEntity> place(int boardSize) {
        List<BoardEntity> entities = new ArrayList<>();
        Set<Integer> usedCells = new HashSet<>(); // every start and end already taken

        for (int i = 0; i < snakeCount; i++) {
            entities.add(placeOne(boardSize, usedCells, true));
        }
        for (int i = 0; i < ladderCount; i++) {
            entities.add(placeOne(boardSize, usedCells, false));
        }
        return entities;
    }

    private BoardEntity placeOne(int boardSize, Set<Integer> usedCells, boolean snake) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS_PER_ENTITY; attempt++) {
            int a = randomBetween(2, boardSize - 1);
            int b = randomBetween(2, boardSize - 1);
            if (a == b || usedCells.contains(a) || usedCells.contains(b)) {
                continue;
            }
            int low = Math.min(a, b);
            int high = Math.max(a, b);
            usedCells.add(low);
            usedCells.add(high);
            return snake ? new Snake(high, low) : new Ladder(low, high);
        }
        throw new InvalidBoardException("Board of size " + boardSize
                + " is too small for " + snakeCount + " snakes and " + ladderCount + " ladders");
    }

    private int randomBetween(int minInclusive, int maxInclusive) {
        return minInclusive + random.nextInt(maxInclusive - minInclusive + 1);
    }
}
