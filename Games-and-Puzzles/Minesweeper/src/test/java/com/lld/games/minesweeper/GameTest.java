package com.lld.games.minesweeper;

import com.lld.games.minesweeper.board.placement.FixedMinePlacementStrategy;
import com.lld.games.minesweeper.board.placement.RandomMinePlacementStrategy;
import com.lld.games.minesweeper.exception.InvalidMoveException;
import com.lld.games.minesweeper.game.Difficulty;
import com.lld.games.minesweeper.game.Game;
import com.lld.games.minesweeper.game.GameStatus;
import com.lld.games.minesweeper.game.MoveResult;
import com.lld.games.minesweeper.listener.GameEventListener;
import com.lld.games.minesweeper.model.Position;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GameTest {

    /** Board with mines at fixed positions and no first-click protection. */
    private static Game.Builder fixed(int rows, int cols, Position... mines) {
        return Game.builder()
                .custom(rows, cols, mines.length)
                .placementStrategy(new FixedMinePlacementStrategy(Set.of(mines)))
                .firstClickSafe(false);
    }

    @Test
    void revealingZeroFloodFillsAndCanWinInOneClick() {
        // 3x3, single mine in the corner. Clicking the far corner opens all 8 safe cells.
        Game game = fixed(3, 3, Position.of(0, 0)).build();

        MoveResult r = game.reveal(2, 2);

        assertEquals(8, r.revealed().size());
        assertEquals(GameStatus.WON, game.getStatus());
    }

    @Test
    void floodFillStopsAtNumberedCells() {
        // 5x5 with a full wall of mines in column 2: left side opens, right side stays hidden.
        Game game = fixed(5, 5,
                Position.of(0, 2), Position.of(1, 2), Position.of(2, 2), Position.of(3, 2), Position.of(4, 2))
                .build();

        MoveResult r = game.reveal(0, 0);

        assertEquals(10, r.revealed().size()); // columns 0 and 1
        assertTrue(game.getBoard().getCell(Position.of(0, 3)).isHidden());
        assertEquals(GameStatus.IN_PROGRESS, game.getStatus());
    }

    @Test
    void revealingNumberOpensOnlyThatCell() {
        Game game = fixed(3, 3, Position.of(0, 0)).build();

        MoveResult r = game.reveal(1, 1);

        assertEquals(List.of(Position.of(1, 1)), r.revealed());
    }

    @Test
    void revealingMineLosesAndShowsAllMines() {
        Game game = fixed(3, 3, Position.of(0, 0), Position.of(2, 2)).build();

        game.reveal(0, 0);

        assertEquals(GameStatus.LOST, game.getStatus());
        assertEquals(Position.of(0, 0), game.getExplodedAt().orElseThrow());
        assertTrue(game.getBoard().getCell(Position.of(2, 2)).isRevealed());
    }

    @Test
    void flaggedCellCannotBeRevealed() {
        Game game = fixed(3, 3, Position.of(0, 0)).build();
        game.reveal(1, 1);
        game.toggleFlag(0, 0);

        MoveResult r = game.reveal(0, 0);

        assertFalse(r.changedBoard());
        assertEquals(GameStatus.IN_PROGRESS, game.getStatus());
    }

    @Test
    void floodFillDoesNotOpenFlaggedCells() {
        Game game = fixed(3, 3, Position.of(0, 0)).build();
        game.toggleFlag(2, 0);

        MoveResult r = game.reveal(2, 2);

        assertFalse(r.revealed().contains(Position.of(2, 0)));
        assertTrue(game.getBoard().getCell(Position.of(2, 0)).isFlagged());
        assertEquals(GameStatus.IN_PROGRESS, game.getStatus()); // (2,0) still hidden under a flag
    }

    @Test
    void toggleFlagUpdatesRemainingCounter() {
        Game game = fixed(3, 3, Position.of(0, 0)).build();

        assertTrue(game.toggleFlag(0, 0));
        assertEquals(0, game.getRemainingFlags());
        assertFalse(game.toggleFlag(0, 0));
        assertEquals(1, game.getRemainingFlags());
    }

    @Test
    void revealedCellCannotBeFlagged() {
        Game game = fixed(3, 3, Position.of(0, 0)).build();
        game.reveal(1, 1);

        assertFalse(game.toggleFlag(1, 1));
        assertEquals(1, game.getRemainingFlags());
    }

    @Test
    void chordWithCorrectFlagsOpensNeighbours() {
        Game game = fixed(3, 3, Position.of(0, 0)).build();
        game.reveal(1, 1);
        game.toggleFlag(0, 0);

        MoveResult r = game.chord(1, 1);

        assertEquals(7, r.revealed().size());
        assertEquals(GameStatus.WON, game.getStatus());
    }

    @Test
    void chordWithWrongFlagLoses() {
        Game game = fixed(3, 3, Position.of(0, 0)).build();
        game.reveal(1, 1);
        game.toggleFlag(0, 1); // wrong cell

        game.chord(1, 1);

        assertEquals(GameStatus.LOST, game.getStatus());
    }

    @Test
    void chordWithoutEnoughFlagsDoesNothing() {
        Game game = fixed(3, 3, Position.of(0, 0)).build();
        game.reveal(1, 1);

        assertFalse(game.chord(1, 1).changedBoard());
    }

    @RepeatedTest(50)
    void firstClickIsAlwaysSafeAndOpensARegion() {
        Game game = Game.builder().difficulty(Difficulty.EXPERT).build();

        MoveResult r = game.reveal(8, 15);

        assertNotEquals(GameStatus.LOST, game.getStatus());
        assertEquals(0, game.getBoard().getCell(Position.of(8, 15)).getAdjacentMines());
        assertTrue(r.revealed().size() >= 9, "safe zone means the first click is a zero");
        assertEquals(99, game.getBoard().getMineCount());
    }

    @Test
    void firstClickSafeOnDenseBoardProtectsAtLeastTheClickedCell() {
        // 3x3 with 8 mines: no room for a 3x3 safe zone, only the clicked cell is spared.
        Game game = Game.builder().custom(3, 3, 8).build();

        game.reveal(1, 1);

        assertEquals(GameStatus.WON, game.getStatus());
    }

    @Test
    void movesAfterGameOverAreRejected() {
        Game game = fixed(3, 3, Position.of(0, 0)).build();
        game.reveal(0, 0);

        assertThrows(InvalidMoveException.class, () -> game.reveal(1, 1));
        assertThrows(InvalidMoveException.class, () -> game.toggleFlag(1, 1));
    }

    @Test
    void outOfBoundsMoveIsRejected() {
        Game game = fixed(3, 3, Position.of(0, 0)).build();

        assertThrows(InvalidMoveException.class, () -> game.reveal(3, 0));
        assertThrows(InvalidMoveException.class, () -> game.toggleFlag(-1, 0));
    }

    @Test
    void listenersReceiveEvents() {
        List<String> events = new ArrayList<>();
        Game game = fixed(3, 3, Position.of(0, 0))
                .addListener(new GameEventListener() {
                    @Override
                    public void onCellsRevealed(List<Position> positions) {
                        events.add("revealed:" + positions.size());
                    }

                    @Override
                    public void onFlagToggled(Position position, boolean flagged) {
                        events.add("flag:" + flagged);
                    }

                    @Override
                    public void onGameWon() {
                        events.add("won");
                    }
                })
                .build();

        game.toggleFlag(0, 0);
        game.reveal(2, 2);

        assertEquals(List.of("flag:true", "revealed:8", "won"), events);
    }

    @Test
    void builderValidatesMineCount() {
        assertThrows(IllegalArgumentException.class, () -> Game.builder().custom(3, 3, 9).build());
        assertThrows(IllegalArgumentException.class, () -> Game.builder().custom(3, 3, 0).build());
    }

    @Test
    void strategyReturningWrongCountIsDetected() {
        Game game = Game.builder()
                .custom(3, 3, 2)
                .placementStrategy(new FixedMinePlacementStrategy(Set.of(Position.of(0, 0))))
                .build();

        assertThrows(IllegalStateException.class, () -> game.reveal(2, 2));
    }

    @Test
    void seededGamesAreReproducible() {
        Game a = Game.builder().difficulty(Difficulty.INTERMEDIATE)
                .placementStrategy(new RandomMinePlacementStrategy(7L)).build();
        Game b = Game.builder().difficulty(Difficulty.INTERMEDIATE)
                .placementStrategy(new RandomMinePlacementStrategy(7L)).build();

        assertEquals(a.reveal(5, 5).revealed(), b.reveal(5, 5).revealed());
    }
}
