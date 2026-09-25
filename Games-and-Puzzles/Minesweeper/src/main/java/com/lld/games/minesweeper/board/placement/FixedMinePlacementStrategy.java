package com.lld.games.minesweeper.board.placement;

import com.lld.games.minesweeper.model.Position;

import java.util.Set;

/**
 * Places mines at exactly the given positions, ignoring {@code excluded}.
 * Used for unit tests and scripted demos. Combine with {@code firstClickSafe(false)}
 * when a test needs to click a mine on purpose.
 */
public class FixedMinePlacementStrategy implements MinePlacementStrategy {

    private final Set<Position> mines;

    public FixedMinePlacementStrategy(Set<Position> mines) {
        this.mines = Set.copyOf(mines);
    }

    @Override
    public Set<Position> placeMines(int rows, int cols, int mineCount, Set<Position> excluded) {
        for (Position p : mines) {
            if (p.row() < 0 || p.row() >= rows || p.col() < 0 || p.col() >= cols) {
                throw new IllegalArgumentException("Mine " + p + " is outside the board");
            }
        }
        return mines;
    }

    public int size() {
        return mines.size();
    }
}
