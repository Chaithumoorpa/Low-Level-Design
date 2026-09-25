package com.lld.games.sudoku;

import com.lld.games.sudoku.board.Board;
import com.lld.games.sudoku.generator.PuzzleGenerator;
import com.lld.games.sudoku.model.Difficulty;
import com.lld.games.sudoku.solver.BacktrackingSolver;
import com.lld.games.sudoku.solver.SudokuSolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class SolverAndGeneratorTest {

    private final SudokuSolver solver = new BacktrackingSolver();

    /** A valid solution: every row, column and box has each digit once, and the givens are kept. */
    private static void assertValidSolution(Board puzzle, int[][] solution) {
        Board solved = Board.fromGrid(solution, puzzle.getBoxRows(), puzzle.getBoxCols(), false);
        assertTrue(solved.isSolved(), "solution breaks the rules");
        int n = puzzle.getSize();
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                if (puzzle.isGiven(r, c)) {
                    assertEquals(puzzle.get(r, c), solution[r][c], "given changed at " + r + "," + c);
                }
            }
        }
    }

    @Test
    void solvesTheClassicPuzzleUniquely() {
        Board puzzle = Board.parse(BoardTest.CLASSIC);

        int[][] solution = solver.solve(puzzle).orElseThrow();

        assertValidSolution(puzzle, solution);
        assertEquals(1, solver.countSolutions(puzzle, 2));
    }

    @Test
    void solvesAVeryHardPuzzle() {
        // A well-known puzzle designed to defeat human solving techniques (only 21 clues).
        Board puzzle = Board.parse(
                "8........" +
                "..36....." +
                ".7..9.2.." +
                ".5...7..." +
                "....457.." +
                "...1...3." +
                "..1....68" +
                "..85...1." +
                ".9....4..");
        assertEquals(21, puzzle.givenCount());
        assertEquals(1, solver.countSolutions(puzzle, 2));

        assertValidSolution(puzzle, solver.solve(puzzle).orElseThrow());
    }

    @Test
    void contradictoryGivensHaveNoSolution() {
        Board puzzle = Board.parse("55" + ".".repeat(79));      // two 5s in row 1

        assertTrue(solver.solve(puzzle).isEmpty());
        assertEquals(0, solver.countSolutions(puzzle, 2));
    }

    @Test
    void unsolvableWithoutDirectConflictIsDetected() {
        // Row 1 holds 1-8 in columns 1-8, and a 9 sits in column 9 lower down: r1c9 has no candidate.
        Board puzzle = Board.parse("12345678." + "........9" + ".".repeat(63));

        assertTrue(solver.solve(puzzle).isEmpty());
    }

    @Test
    void emptyBoardHasManySolutions() {
        assertEquals(2, solver.countSolutions(new Board(3, 3), 2));   // stops at the limit
    }

    @ParameterizedTest(name = "{0}x{1} boxes")
    @CsvSource({"2,2", "2,3", "3,3"})
    void generatedPuzzlesHaveExactlyOneSolution(int boxRows, int boxCols) {
        PuzzleGenerator generator = new PuzzleGenerator(7L);
        for (Difficulty d : Difficulty.values()) {
            Board puzzle = generator.generate(boxRows, boxCols, d);

            assertEquals(1, solver.countSolutions(puzzle, 2), d + " puzzle must be unique");
            assertEquals(puzzle.givenCount(), puzzle.filledCount(), "every filled cell is a clue");
            assertTrue(puzzle.conflictingCells().isEmpty());
        }
    }

    @ParameterizedTest
    @EnumSource(Difficulty.class)
    void harderLevelsGiveFewerClues(Difficulty difficulty) {
        Board puzzle = new PuzzleGenerator(11L).generate(difficulty);
        int target = difficulty.targetClues(81);

        // Digging stops at the target; it may stay slightly above if no more cells can be removed.
        assertTrue(puzzle.givenCount() >= target && puzzle.givenCount() <= target + 8,
                difficulty + " gave " + puzzle.givenCount() + " clues, target " + target);
    }

    @Test
    void sameSeedSamePuzzle() {
        assertEquals(new PuzzleGenerator(99L).generate(Difficulty.MEDIUM).toLine(),
                new PuzzleGenerator(99L).generate(Difficulty.MEDIUM).toLine());
    }
}
