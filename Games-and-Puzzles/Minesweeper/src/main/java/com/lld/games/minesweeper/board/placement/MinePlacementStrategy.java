package com.lld.games.minesweeper.board.placement;

import com.lld.games.minesweeper.model.Position;

import java.util.Set;

/**
 * Strategy pattern: decides where the mines go. Random in real games, fixed in tests,
 * and could be "no-guess solvable" layouts in an advanced version.
 */
public interface MinePlacementStrategy {

    /**
     * @param excluded cells that must stay mine-free (e.g. the first click and its neighbours)
     * @return exactly {@code mineCount} distinct positions
     */
    Set<Position> placeMines(int rows, int cols, int mineCount, Set<Position> excluded);
}
