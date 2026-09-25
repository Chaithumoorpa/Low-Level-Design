package com.lld.games.tictactoe;

import com.lld.games.tictactoe.exception.InvalidMoveException;
import com.lld.games.tictactoe.game.Game;
import com.lld.games.tictactoe.game.GameStatus;
import com.lld.games.tictactoe.listener.GameEventListener;
import com.lld.games.tictactoe.model.Move;
import com.lld.games.tictactoe.model.Player;
import com.lld.games.tictactoe.model.Symbol;
import com.lld.games.tictactoe.render.ConsoleBoardRenderer;
import com.lld.games.tictactoe.win.ScanWinningStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameTest {

    private static Game newGame(int size) {
        return Game.builder().boardSize(size).addPlayer("Alice").addPlayer("Bob").build();
    }

    /** Plays "row,col" pairs, alternating X and O. */
    private static void play(Game game, int[]... moves) {
        for (int[] m : moves) {
            game.makeMove(m[0], m[1]);
        }
    }

    private static int[] at(int row, int col) {
        return new int[]{row, col};
    }

    @Test
    void xMovesFirstAndTurnsAlternate() {
        Game game = newGame(3);
        assertEquals("Alice", game.getCurrentPlayer().name());
        assertEquals(Symbol.X, game.getCurrentPlayer().symbol());

        game.makeMove(0, 0);

        assertEquals("Bob", game.getCurrentPlayer().name());
        assertEquals(Symbol.X, game.getBoard().get(0, 0));
    }

    @Test
    void rowWin() {
        Game game = newGame(3);
        play(game, at(0, 0), at(1, 0), at(0, 1), at(1, 1), at(0, 2));

        assertEquals(GameStatus.WON, game.getStatus());
        assertEquals("Alice", game.getWinner().orElseThrow().name());
    }

    @Test
    void columnWin() {
        Game game = newGame(3);
        play(game, at(0, 0), at(0, 1), at(1, 0), at(1, 1), at(2, 2), at(2, 1));

        assertEquals("Bob", game.getWinner().orElseThrow().name());
    }

    @Test
    void mainDiagonalWin() {
        Game game = newGame(3);
        play(game, at(0, 0), at(0, 1), at(1, 1), at(0, 2), at(2, 2));

        assertEquals(GameStatus.WON, game.getStatus());
    }

    @Test
    void antiDiagonalWin() {
        Game game = newGame(3);
        play(game, at(0, 2), at(0, 0), at(1, 1), at(0, 1), at(2, 0));

        assertEquals(GameStatus.WON, game.getStatus());
    }

    @Test
    void fullBoardWithoutLineIsDraw() {
        // X O X
        // X O O
        // O X X
        Game game = newGame(3);
        play(game, at(0, 0), at(0, 1), at(0, 2), at(1, 1), at(1, 0),
                at(1, 2), at(2, 1), at(2, 0), at(2, 2));

        assertEquals(GameStatus.DRAW, game.getStatus());
        assertTrue(game.getWinner().isEmpty());
    }

    @Test
    void winOnLastCellIsAWinNotADraw() {
        // X O X
        // O X O
        // O X X  <- last move (2,2) fills the board AND completes the main diagonal
        Game game = newGame(3);
        play(game, at(0, 0), at(0, 1), at(0, 2), at(1, 0), at(2, 1),
                at(1, 2), at(1, 1), at(2, 0));
        assertEquals(GameStatus.IN_PROGRESS, game.getStatus());

        game.makeMove(2, 2);

        assertEquals(GameStatus.WON, game.getStatus());
    }

    @Test
    void largerBoardNeedsTheFullLine() {
        Game game = newGame(4);
        play(game, at(0, 0), at(3, 0), at(0, 1), at(3, 1), at(0, 2), at(3, 2));
        assertEquals(GameStatus.IN_PROGRESS, game.getStatus());

        game.makeMove(0, 3);

        assertEquals(GameStatus.WON, game.getStatus());
    }

    @Test
    void occupiedCellIsRejectedAndTurnDoesNotChange() {
        Game game = newGame(3);
        game.makeMove(1, 1);

        assertThrows(InvalidMoveException.class, () -> game.makeMove(1, 1));
        assertEquals("Bob", game.getCurrentPlayer().name());
        assertEquals(1, game.getHistory().size());
    }

    @Test
    void outOfBoundsIsRejected() {
        Game game = newGame(3);

        assertThrows(InvalidMoveException.class, () -> game.makeMove(3, 0));
        assertThrows(InvalidMoveException.class, () -> game.makeMove(0, -1));
    }

    @Test
    void noMovesAfterGameOver() {
        Game game = newGame(3);
        play(game, at(0, 0), at(1, 0), at(0, 1), at(1, 1), at(0, 2));

        assertThrows(InvalidMoveException.class, () -> game.makeMove(2, 2));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 11, -1})
    void boardSizeOutsideRangeIsRejected(int size) {
        assertThrows(IllegalArgumentException.class, () -> newGame(size));
    }

    @Test
    void exactlyTwoPlayersAreRequired() {
        assertThrows(IllegalStateException.class, () -> Game.builder().addPlayer("Solo").build());
        assertThrows(IllegalStateException.class,
                () -> Game.builder().addPlayer("A").addPlayer("B").addPlayer("C"));
        assertThrows(IllegalArgumentException.class, () -> new Player(" ", Symbol.X));
    }

    @Test
    void scanStrategyGivesSameResults() {
        Game game = Game.builder().addPlayer("A").addPlayer("B")
                .winningStrategy(new ScanWinningStrategy()).build();
        play(game, at(0, 2), at(0, 0), at(1, 1), at(0, 1), at(2, 0));

        assertEquals(GameStatus.WON, game.getStatus());
    }

    @Test
    void historyAndListenersRecordEveryMove() {
        List<String> events = new ArrayList<>();
        Game game = Game.builder().addPlayer("A").addPlayer("B")
                .addListener(new GameEventListener() {
                    @Override
                    public void onMove(Move move) {
                        events.add(move.symbol() + "@" + move.row() + move.col());
                    }

                    @Override
                    public void onGameOver(GameStatus status, Player winner) {
                        events.add(status + ":" + winner.name());
                    }
                })
                .build();

        play(game, at(0, 0), at(1, 0), at(0, 1), at(1, 1), at(0, 2));

        assertEquals(List.of("X@00", "O@10", "X@01", "O@11", "X@02", "WON:A"), events);
        assertEquals(5, game.getHistory().size());
    }

    @Test
    void rendererDrawsSymbolsAndGrid() {
        Game game = newGame(3);
        play(game, at(0, 0), at(1, 1));

        assertEquals(
                "    0   1   2\n"
                        + "0   X |   |\n"
                        + "   -----------\n"
                        + "1     | O |\n"
                        + "   -----------\n"
                        + "2     |   |\n",
                new ConsoleBoardRenderer().render(game.getBoard()));
    }
}
