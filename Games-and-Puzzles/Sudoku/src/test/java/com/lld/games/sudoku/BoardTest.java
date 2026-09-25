package com.lld.games.sudoku;

import com.lld.games.sudoku.board.Board;
import com.lld.games.sudoku.exception.InvalidMoveException;
import com.lld.games.sudoku.model.Position;
import com.lld.games.sudoku.render.ConsoleBoardRenderer;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BoardTest {

    static final String CLASSIC =
            "53..7...." +
            "6..195..." +
            ".98....6." +
            "8...6...3" +
            "4..8.3..1" +
            "7...2...6" +
            ".6....28." +
            "...419..5" +
            "....8..79";

    @Test
    void parseMarksGivensAndRoundTrips() {
        Board board = Board.parse(CLASSIC);

        assertEquals(5, board.get(0, 0));
        assertTrue(board.isGiven(0, 0));
        assertTrue(board.isEmpty(0, 2));
        assertFalse(board.isGiven(0, 2));
        assertEquals(30, board.givenCount());
        assertEquals(CLASSIC, board.toLine());
    }

    @Test
    void givenCluesCannotBeChanged() {
        Board board = Board.parse(CLASSIC);

        assertThrows(InvalidMoveException.class, () -> board.set(0, 0, 1));
        assertThrows(InvalidMoveException.class, () -> board.set(0, 0, 0));
    }

    @Test
    void conflictsInRowColumnAndBox() {
        Board board = Board.parse(CLASSIC);

        assertTrue(board.conflicts(0, 2, 5));   // 5 already in row 1
        assertTrue(board.conflicts(0, 2, 8));   // 8 already in column 3
        assertTrue(board.conflicts(0, 2, 6));   // 6 already in the top-left box
        assertFalse(board.conflicts(0, 2, 4));
        assertEquals("5 is already in row 1", board.conflictReason(0, 2, 5));
        assertEquals("8 is already in column 3", board.conflictReason(0, 2, 8));
        assertEquals("6 is already in this box", board.conflictReason(0, 2, 6));
    }

    @Test
    void cellDoesNotConflictWithItself() {
        Board board = Board.parse(CLASSIC);
        board.set(0, 2, 4);

        assertFalse(board.conflicts(0, 2, 4));
    }

    @Test
    void conflictingCellsReportsBothSides() {
        Board board = Board.parse(CLASSIC);
        board.set(0, 2, 5);                      // duplicate 5 in row 1

        Set<Position> conflicts = board.conflictingCells();

        assertTrue(conflicts.contains(Position.of(0, 0)));
        assertTrue(conflicts.contains(Position.of(0, 2)));
        board.set(0, 2, 0);
        assertTrue(board.conflictingCells().isEmpty());
    }

    @Test
    void candidateMaskListsLegalDigits() {
        Board board = Board.parse(CLASSIC);
        int mask = board.candidateMask(0, 2);

        // r1c3: row has 5,3,7; column has 8; box has 5,3,6,9,8 → candidates 1,2,4
        assertEquals((1 << 1) | (1 << 2) | (1 << 4), mask);
    }

    @Test
    void rectangularBoxesForSixBySix() {
        Board board = new Board(2, 3);            // 6x6, boxes 2 rows x 3 cols

        assertEquals(6, board.getSize());
        assertEquals(0, board.boxIndex(1, 2));
        assertEquals(1, board.boxIndex(0, 3));
        assertEquals(2, board.boxIndex(2, 0));
        assertEquals(5, board.boxIndex(5, 5));
        board.set(0, 0, 4);
        assertTrue(board.conflicts(1, 2, 4));     // same box
        assertFalse(board.conflicts(1, 3, 4));    // next box, different row and column
    }

    @Test
    void invalidInputIsRejected() {
        Board board = new Board(3, 3);

        assertThrows(InvalidMoveException.class, () -> board.set(9, 0, 1));
        assertThrows(InvalidMoveException.class, () -> board.set(0, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> Board.parse("123"));
        assertThrows(IllegalArgumentException.class, () -> Board.parse("5".repeat(15) + ".", 2, 2));
        assertThrows(IllegalArgumentException.class, () -> new Board(5, 5));
    }

    @Test
    void rendererDrawsBoxes() {
        Board board = Board.parse("1......2...3...4", 2, 2);

        assertEquals(
                "     1 2   3 4\n"
                        + "   +-----+-----+\n"
                        + " 1 | 1 . | . . |\n"
                        + " 2 | . . | . 2 |\n"
                        + "   +-----+-----+\n"
                        + " 3 | . . | . 3 |\n"
                        + " 4 | . . | . 4 |\n"
                        + "   +-----+-----+\n",
                new ConsoleBoardRenderer().render(board));
    }
}
