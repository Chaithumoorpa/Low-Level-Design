package com.lld.games.snakeandladder;

import com.lld.games.snakeandladder.board.placement.ManualPlacementStrategy;
import com.lld.games.snakeandladder.dice.FixedSequenceDice;
import com.lld.games.snakeandladder.game.Game;
import com.lld.games.snakeandladder.game.GameStatus;
import com.lld.games.snakeandladder.game.OvershootPolicy;
import com.lld.games.snakeandladder.game.TurnResult;
import com.lld.games.snakeandladder.listener.GameEventListener;
import com.lld.games.snakeandladder.model.Ladder;
import com.lld.games.snakeandladder.model.Player;
import com.lld.games.snakeandladder.model.Snake;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameTest {

    private Game.Builder twoPlayers(int boardSize, int... rolls) {
        return Game.builder()
                .boardSize(boardSize)
                .dice(new FixedSequenceDice(rolls))
                .addPlayer("A")
                .addPlayer("B");
    }

    @Test
    void ladderMovesPlayerUp() {
        Game game = twoPlayers(100, 3)
                .placementStrategy(new ManualPlacementStrategy(List.of(new Ladder(3, 50))))
                .build();

        TurnResult r = game.playTurn();

        assertEquals(3, r.landedOn());
        assertEquals(50, r.to());
        assertInstanceOf(Ladder.class, r.entity());
        assertEquals(50, r.player().getPosition());
    }

    @Test
    void snakeMovesPlayerDown() {
        Game game = twoPlayers(100, 5)
                .placementStrategy(new ManualPlacementStrategy(List.of(new Snake(5, 1))))
                .build();

        TurnResult r = game.playTurn();

        assertEquals(1, r.to());
        assertInstanceOf(Snake.class, r.entity());
    }

    @Test
    void playersTakeTurnsInOrder() {
        Game game = twoPlayers(100, 1).build();

        assertEquals("A", game.getCurrentPlayer().getName());
        game.playTurn();
        assertEquals("B", game.getCurrentPlayer().getName());
        game.playTurn();
        assertEquals("A", game.getCurrentPlayer().getName());
    }

    @Test
    void overshootingLastCellKeepsPlayerInPlace() {
        // Board of 10: A rolls 6 (->6), B rolls 1, A rolls 6 (would be 12 > 10, stays at 6)
        Game game = twoPlayers(10, 6, 1, 6).build();
        game.playTurn();
        game.playTurn();

        TurnResult r = game.playTurn();

        assertTrue(r.overshot());
        assertEquals(6, r.to());
        assertEquals(GameStatus.IN_PROGRESS, game.getStatus());
    }

    @Test
    void bounceBackPolicyReflectsExcessRoll() {
        // Board of 10: A 8, B 1, A 4 -> 12 is 2 past 10, bounces to 8
        Game game = twoPlayers(10, 8, 1, 4)
                .overshootPolicy(OvershootPolicy.BOUNCE_BACK)
                .build();
        game.playTurn();
        game.playTurn();

        TurnResult r = game.playTurn();

        assertTrue(r.overshot());
        assertEquals(8, r.landedOn());
        assertEquals(8, r.to());
    }

    @Test
    void bounceBackCanLandOnSnake() {
        Game game = twoPlayers(10, 6, 1, 6)
                .overshootPolicy(OvershootPolicy.BOUNCE_BACK)
                .placementStrategy(new ManualPlacementStrategy(List.of(new Snake(8, 2))))
                .build();
        game.playTurn(); // A: 0 -> 6
        game.playTurn(); // B: 0 -> 1

        TurnResult r = game.playTurn(); // A: 6 + 6 = 12, bounces to 8, snake -> 2

        assertTrue(r.overshot());
        assertEquals(8, r.landedOn());
        assertEquals(2, r.to());
    }

    @Test
    void exactRollWinsAndEndsGame() {
        // Board of 10: A 6, B 1, A 4 -> exactly 10
        Game game = twoPlayers(10, 6, 1, 4).build();

        Player winner = game.play();

        assertEquals("A", winner.getName());
        assertEquals(GameStatus.FINISHED, game.getStatus());
        assertThrows(IllegalStateException.class, game::playTurn);
    }

    @Test
    void ladderToLastCellWins() {
        Game game = twoPlayers(20, 4)
                .placementStrategy(new ManualPlacementStrategy(List.of(new Ladder(4, 20))))
                .build();

        TurnResult r = game.playTurn();

        assertTrue(r.won());
        assertEquals("A", game.getWinner().orElseThrow().getName());
    }

    @Test
    void listenersAreNotified() {
        List<String> events = new ArrayList<>();
        Game game = twoPlayers(4, 4)
                .addListener(new GameEventListener() {
                    @Override
                    public void onTurn(TurnResult result) {
                        events.add("turn:" + result.player().getName());
                    }

                    @Override
                    public void onGameOver(Player winner) {
                        events.add("win:" + winner.getName());
                    }
                })
                .build();

        game.play();

        assertEquals(List.of("turn:A", "win:A"), events);
    }

    @Test
    void builderRejectsFewerThanTwoPlayers() {
        Game.Builder builder = Game.builder().addPlayer("Solo");
        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    void builderRejectsDuplicateNames() {
        Game.Builder builder = Game.builder().addPlayer("A").addPlayer("A");
        assertThrows(IllegalStateException.class, builder::build);
    }
}
