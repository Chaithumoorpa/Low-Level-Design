package com.lld.games.minesweeper;

import com.lld.games.minesweeper.board.Board;
import com.lld.games.minesweeper.board.placement.RandomMinePlacementStrategy;
import com.lld.games.minesweeper.model.Cell;
import com.lld.games.minesweeper.model.CellState;
import com.lld.games.minesweeper.model.Position;
import com.lld.games.minesweeper.render.ConsoleBoardRenderer;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BoardTest {

    @Test
    void adjacentCountsAreComputed() {
        Board board = new Board(3, 3);
        board.placeMines(Set.of(Position.of(0, 0), Position.of(0, 2)));

        assertEquals(2, board.getCell(Position.of(0, 1)).getAdjacentMines());
        assertEquals(2, board.getCell(Position.of(1, 1)).getAdjacentMines());
        assertEquals(1, board.getCell(Position.of(1, 0)).getAdjacentMines());
        assertEquals(0, board.getCell(Position.of(2, 1)).getAdjacentMines());
    }

    @Test
    void neighboursRespectEdges() {
        Board board = new Board(3, 3);

        assertEquals(3, board.neighbours(Position.of(0, 0)).size());
        assertEquals(5, board.neighbours(Position.of(0, 1)).size());
        assertEquals(8, board.neighbours(Position.of(1, 1)).size());
    }

    @Test
    void minesCanOnlyBePlacedOnce() {
        Board board = new Board(2, 2);
        board.placeMines(Set.of(Position.of(0, 0)));

        assertThrows(IllegalStateException.class, () -> board.placeMines(Set.of(Position.of(1, 1))));
    }

    @Test
    void floodFillOnHugeEmptyBoardDoesNotOverflowStack() {
        Board board = new Board(1000, 1000);
        board.placeMines(Set.of(Position.of(999, 999)));

        List<Position> revealed = board.revealFrom(Position.of(0, 0));

        assertEquals(1000 * 1000 - 1, revealed.size());
        assertTrue(board.allSafeCellsRevealed());
    }

    @Test
    void cellStateTransitions() {
        Cell cell = new Cell(Position.of(0, 0));

        assertTrue(cell.toggleFlag());
        assertEquals(CellState.FLAGGED, cell.getState());
        assertFalse(cell.reveal(), "flagged cells cannot be revealed");
        assertTrue(cell.toggleFlag());
        assertTrue(cell.reveal());
        assertFalse(cell.toggleFlag(), "revealed cells cannot be flagged");
        assertFalse(cell.reveal(), "second reveal is a no-op");
    }

    @Test
    void randomPlacementRespectsCountAndExclusions() {
        Set<Position> excluded = new HashSet<>();
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                excluded.add(Position.of(r, c));
            }
        }

        Set<Position> mines = new RandomMinePlacementStrategy(1L).placeMines(10, 10, 91, excluded);

        assertEquals(91, mines.size());
        mines.forEach(m -> assertFalse(excluded.contains(m)));
    }

    @Test
    void randomPlacementRejectsTooManyMines() {
        assertThrows(IllegalArgumentException.class,
                () -> new RandomMinePlacementStrategy(1L).placeMines(2, 2, 4, Set.of(Position.of(0, 0))));
    }

    @Test
    void rendererShowsEachSymbol() {
        Board board = new Board(2, 3);
        board.placeMines(Set.of(Position.of(0, 0)));
        board.getCell(Position.of(1, 2)).toggleFlag();
        board.revealFrom(Position.of(0, 1));
        board.revealFrom(Position.of(0, 2));
        board.revealAllMines();

        String out = new ConsoleBoardRenderer().render(board, Position.of(0, 0));

        assertEquals(
                "  0 1 2\n"
                        + "0 X 1 .\n"
                        + "1 # 1 F\n", out);
    }
}
