package com.lld.games.sudoku.generator;

import com.lld.games.sudoku.board.Board;
import com.lld.games.sudoku.model.Difficulty;
import com.lld.games.sudoku.solver.BacktrackingSolver;
import com.lld.games.sudoku.solver.SudokuSolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Builds puzzles with exactly one solution in two steps:
 * <ol>
 *   <li><b>Fill</b>: solve an empty board with randomised candidate order, giving a random complete grid.</li>
 *   <li><b>Dig</b>: visit cells in random order and blank each one, but only keep the blank if the
 *       puzzle still has a unique solution ({@code countSolutions(board, 2) == 1}).</li>
 * </ol>
 * Digging stops at the difficulty's target clue count, or when no more cells can be removed.
 */
public class PuzzleGenerator {

    private final Random random;
    private final SudokuSolver checker = new BacktrackingSolver();

    public PuzzleGenerator() {
        this(new Random());
    }

    public PuzzleGenerator(long seed) {
        this(new Random(seed));
    }

    public PuzzleGenerator(Random random) {
        this.random = random;
    }

    public Board generate(Difficulty difficulty) {
        return generate(3, 3, difficulty);
    }

    public Board generate(int boxRows, int boxCols, Difficulty difficulty) {
        int[][] grid = new BacktrackingSolver(random)
                .solve(new Board(boxRows, boxCols))
                .orElseThrow(() -> new IllegalStateException("An empty board always has a solution"));

        int n = grid.length;
        int target = Math.max(difficulty.targetClues(n * n), 1);
        int clues = n * n;

        List<Integer> cells = new ArrayList<>(n * n);
        for (int i = 0; i < n * n; i++) {
            cells.add(i);
        }
        Collections.shuffle(cells, random);

        for (int cell : cells) {
            if (clues <= target) {
                break;
            }
            int r = cell / n;
            int c = cell % n;
            int saved = grid[r][c];
            grid[r][c] = 0;
            Board candidate = Board.fromGrid(grid, boxRows, boxCols, true);
            if (checker.countSolutions(candidate, 2) == 1) {
                clues--;                       // still unique: keep the hole
            } else {
                grid[r][c] = saved;            // two solutions would appear: put it back
            }
        }
        return Board.fromGrid(grid, boxRows, boxCols, true);
    }
}
