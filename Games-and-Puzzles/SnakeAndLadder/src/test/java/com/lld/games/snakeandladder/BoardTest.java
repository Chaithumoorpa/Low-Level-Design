package com.lld.games.snakeandladder;

import com.lld.games.snakeandladder.board.Board;
import com.lld.games.snakeandladder.board.placement.RandomPlacementStrategy;
import com.lld.games.snakeandladder.dice.StandardDice;
import com.lld.games.snakeandladder.exception.InvalidBoardException;
import com.lld.games.snakeandladder.model.BoardEntity;
import com.lld.games.snakeandladder.model.Ladder;
import com.lld.games.snakeandladder.model.Snake;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BoardTest {

    @Test
    void finalPositionFollowsEntities() {
        Board board = new Board(100, List.of(new Snake(99, 10), new Ladder(2, 40)));

        assertEquals(10, board.getFinalPosition(99));
        assertEquals(40, board.getFinalPosition(2));
        assertEquals(50, board.getFinalPosition(50));
    }

    @Test
    void entitiesValidateTheirDirection() {
        assertThrows(IllegalArgumentException.class, () -> new Snake(10, 20));
        assertThrows(IllegalArgumentException.class, () -> new Ladder(20, 10));
    }

    @Test
    void rejectsEntityOutsideBoard() {
        assertThrows(InvalidBoardException.class, () -> new Board(50, List.of(new Ladder(10, 60))));
    }

    @Test
    void rejectsEntityOnLastCell() {
        assertThrows(InvalidBoardException.class, () -> new Board(100, List.of(new Snake(100, 1))));
    }

    @Test
    void rejectsTwoEntitiesOnSameCell() {
        assertThrows(InvalidBoardException.class,
                () -> new Board(100, List.of(new Snake(50, 10), new Ladder(50, 90))));
    }

    @Test
    void rejectsChainedEntities() {
        // Ladder ends at 30 where a snake starts -> chain (and potential loop)
        assertThrows(InvalidBoardException.class,
                () -> new Board(100, List.of(new Ladder(5, 30), new Snake(30, 5))));
    }

    @RepeatedTest(20)
    void randomPlacementAlwaysProducesAValidBoard() {
        long seed = new Random().nextLong();
        List<BoardEntity> entities = new RandomPlacementStrategy(8, 8, seed).place(100);

        Board board = assertDoesNotThrow(() -> new Board(100, entities), "seed=" + seed);

        assertEquals(16, board.getEntities().size());
        Set<Integer> starts = new HashSet<>();
        board.getEntities().forEach(e -> starts.add(e.getStart()));
        assertEquals(16, starts.size());
    }

    @Test
    void randomPlacementIsReproducibleWithSeed() {
        assertEquals(
                new RandomPlacementStrategy(5, 5, 7L).place(100).toString(),
                new RandomPlacementStrategy(5, 5, 7L).place(100).toString());
    }

    @Test
    void randomPlacementFailsWhenBoardTooSmall() {
        assertThrows(InvalidBoardException.class, () -> new RandomPlacementStrategy(5, 5, 1L).place(10));
    }

    @Test
    void multipleDiceStayInRange() {
        StandardDice dice = new StandardDice(3, 6);
        for (int i = 0; i < 1_000; i++) {
            int roll = dice.roll();
            assertTrue(roll >= 3 && roll <= 18, "roll=" + roll);
        }
    }
}
