package com.lld.games.sudoku;

import com.lld.games.sudoku.board.Board;
import com.lld.games.sudoku.exception.InvalidMoveException;
import com.lld.games.sudoku.game.Game;
import com.lld.games.sudoku.game.GameStatus;
import com.lld.games.sudoku.game.ValidationMode;
import com.lld.games.sudoku.listener.GameEventListener;
import com.lld.games.sudoku.model.Position;
import com.lld.games.sudoku.solver.BacktrackingSolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GameTest {

    private static Game classic() {
        return new Game(Board.parse(BoardTest.CLASSIC));
    }

    private static Game lenient() {
        return new Game(Board.parse(BoardTest.CLASSIC), ValidationMode.LENIENT, new BacktrackingSolver());
    }

    /** Fills every empty cell with the correct value except those listed. */
    private static void fillAllBut(Game game, Position... skip) {
        Set<Position> skipped = Set.of(skip);
        int n = game.getBoard().getSize();
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                if (game.getBoard().isEmpty(r, c) && !skipped.contains(Position.of(r, c))) {
                    game.place(r, c, game.solutionAt(r, c));
                }
            }
        }
    }

    @Test
    void placeAndClear() {
        Game game = classic();
        game.place(0, 2, 4);
        assertEquals(4, game.getBoard().get(0, 2));

        game.clear(0, 2);
        assertTrue(game.getBoard().isEmpty(0, 2));
    }

    @Test
    void givenCellsAreProtected() {
        Game game = classic();

        assertThrows(InvalidMoveException.class, () -> game.place(0, 0, 1));
        assertThrows(InvalidMoveException.class, () -> game.clear(0, 0));
    }

    @Test
    void strictModeRejectsConflictsWithReason() {
        Game game = classic();

        InvalidMoveException e = assertThrows(InvalidMoveException.class, () -> game.place(0, 2, 5));
        assertTrue(e.getMessage().contains("already in row 1"));
        assertTrue(game.getBoard().isEmpty(0, 2));
    }

    @Test
    void lenientModeAcceptsConflictsButReportsThem() {
        Game game = lenient();
        game.place(0, 2, 5);

        assertTrue(game.getConflicts().contains(Position.of(0, 2)));
        assertTrue(game.getConflicts().contains(Position.of(0, 0)));
    }

    @Test
    void invalidValuesAndPositionsAreRejected() {
        Game game = classic();

        assertThrows(InvalidMoveException.class, () -> game.place(0, 2, 0));
        assertThrows(InvalidMoveException.class, () -> game.place(0, 2, 10));
        assertThrows(InvalidMoveException.class, () -> game.place(9, 9, 1));
    }

    @Test
    void undoAndRedo() {
        Game game = classic();
        game.place(0, 2, 4);
        game.place(0, 3, 6);

        assertTrue(game.undo());
        assertTrue(game.getBoard().isEmpty(0, 3));
        assertTrue(game.undo());
        assertTrue(game.getBoard().isEmpty(0, 2));
        assertFalse(game.undo());

        assertTrue(game.redo());
        assertEquals(4, game.getBoard().get(0, 2));
    }

    @Test
    void newMoveClearsRedoHistory() {
        Game game = classic();
        game.place(0, 2, 4);
        game.undo();
        assertTrue(game.canRedo());

        game.place(0, 2, 1);

        assertFalse(game.canRedo());
    }

    @Test
    void undoRestoresOverwrittenValue() {
        Game game = classic();
        game.place(0, 2, 4);
        game.place(0, 2, 1);

        game.undo();

        assertEquals(4, game.getBoard().get(0, 2));
    }

    @Test
    void notesToggleAndAreClearedByAValueButRestoredByUndo() {
        Game game = classic();
        game.toggleNote(0, 2, 1);
        game.toggleNote(0, 2, 4);
        game.toggleNote(0, 2, 1);
        assertEquals(Set.of(4), game.getBoard().getNotes(0, 2));

        game.place(0, 2, 4);
        assertTrue(game.getBoard().getNotes(0, 2).isEmpty());

        game.undo();
        assertEquals(Set.of(4), game.getBoard().getNotes(0, 2));
    }

    @Test
    void notesOnlyOnEmptyCells() {
        Game game = classic();

        assertThrows(InvalidMoveException.class, () -> game.toggleNote(0, 0, 1));
    }

    @Test
    void hintFillsACorrectValue() {
        Game game = classic();

        Position p = game.hint().orElseThrow();

        assertEquals(game.solutionAt(p.row(), p.col()), game.getBoard().get(p.row(), p.col()));
        assertEquals(1, game.getHintsUsed());
    }

    @Test
    void hintFixesAWrongEntryFirst() {
        Game game = lenient();
        // Lenient mode lets the player enter a wrong digit; the hint should correct it first.
        int correct = game.solutionAt(0, 2);
        int wrong = correct == 1 ? 2 : 1;
        game.place(0, 2, wrong);

        Position p = game.hint().orElseThrow();

        assertEquals(Position.of(0, 2), p);
        assertEquals(correct, game.getBoard().get(0, 2));
    }

    @Test
    void fillingTheLastCellSolvesThePuzzle() {
        Game game = classic();
        List<String> events = new ArrayList<>();
        game.addListener(new GameEventListener() {
            @Override
            public void onSolved(int movesMade, int hintsUsed) {
                events.add("solved:" + movesMade + ":" + hintsUsed);
            }
        });

        fillAllBut(game, Position.of(8, 6));
        assertEquals(GameStatus.IN_PROGRESS, game.getStatus());
        game.hint();

        assertEquals(GameStatus.SOLVED, game.getStatus());
        assertEquals(List.of("solved:51:1"), events);
        assertThrows(InvalidMoveException.class, () -> game.place(0, 2, 1));
    }

    @Test
    void fullBoardWithConflictsIsNotSolved() {
        Game game = lenient();
        fillAllBut(game, Position.of(0, 2));
        int wrong = game.solutionAt(0, 2) == 1 ? 2 : 1;

        game.place(0, 2, wrong);

        assertTrue(game.getBoard().isFull());
        assertEquals(GameStatus.IN_PROGRESS, game.getStatus());
    }

    @Test
    void undoAfterSolvingReopensThePuzzle() {
        Game game = classic();
        fillAllBut(game);
        assertEquals(GameStatus.SOLVED, game.getStatus());

        game.undo();

        assertEquals(GameStatus.IN_PROGRESS, game.getStatus());
    }

    @Test
    void listenersSeeMovesUndosAndRedos() {
        Game game = classic();
        List<String> changes = new ArrayList<>();
        game.addListener(new GameEventListener() {
            @Override
            public void onCellChanged(Position position, int oldValue, int newValue) {
                changes.add(position + ":" + oldValue + "->" + newValue);
            }
        });

        game.place(0, 2, 4);
        game.undo();
        game.redo();
        game.toggleNote(0, 3, 2);              // notes are not value changes

        assertEquals(List.of("r1c3:0->4", "r1c3:4->0", "r1c3:0->4"), changes);
    }

    @Test
    void puzzleWithoutSolutionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Game(Board.parse("55" + ".".repeat(79))));
    }
}
