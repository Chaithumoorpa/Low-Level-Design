package com.lld.games.sudoku.solver;

import com.lld.games.sudoku.board.Board;

import java.util.Optional;

/**
 * Strategy pattern: how a puzzle is solved. The game (for hints) and the generator (for the
 * uniqueness check) depend only on this interface, so a smarter solver such as Dancing Links or a
 * human-technique solver can be swapped in later.
 */
public interface SudokuSolver {

    /** A full solution consistent with the board's current values, if one exists. */
    Optional<int[][]> solve(Board board);

    /**
     * Counts solutions, stopping early at {@code limit}. {@code countSolutions(b, 2) == 1}
     * is the standard "has exactly one solution" test.
     */
    int countSolutions(Board board, int limit);
}
