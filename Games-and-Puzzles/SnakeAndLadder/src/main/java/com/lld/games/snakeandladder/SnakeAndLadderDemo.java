package com.lld.games.snakeandladder;

import com.lld.games.snakeandladder.board.placement.ManualPlacementStrategy;
import com.lld.games.snakeandladder.board.placement.RandomPlacementStrategy;
import com.lld.games.snakeandladder.dice.StandardDice;
import com.lld.games.snakeandladder.game.Game;
import com.lld.games.snakeandladder.game.OvershootPolicy;
import com.lld.games.snakeandladder.listener.ConsoleGameEventListener;
import com.lld.games.snakeandladder.model.BoardEntity;
import com.lld.games.snakeandladder.model.Ladder;
import com.lld.games.snakeandladder.model.Player;
import com.lld.games.snakeandladder.model.Snake;

import java.util.List;

/** Entry point: runs a classic game, then a game showing both extensions. */
public class SnakeAndLadderDemo {

    public static void main(String[] args) {
        classicGame();
        extendedGame();
    }

    /** 10x10 board, one six-sided die, hand-placed snakes and ladders. */
    private static void classicGame() {
        banner("Classic game: 100 cells, 1 die, fixed board");

        List<BoardEntity> entities = List.of(
                new Snake(17, 7), new Snake(54, 34), new Snake(62, 19),
                new Snake(64, 60), new Snake(87, 36), new Snake(93, 73),
                new Snake(95, 75), new Snake(98, 79),
                new Ladder(2, 38), new Ladder(4, 14), new Ladder(9, 31),
                new Ladder(21, 42), new Ladder(28, 84), new Ladder(51, 67),
                new Ladder(72, 91), new Ladder(80, 99));

        Game game = Game.builder()
                .boardSize(100)
                .placementStrategy(new ManualPlacementStrategy(entities))
                .dice(new StandardDice())
                .addPlayer("Alice")
                .addPlayer("Bob")
                .addPlayer("Charlie")
                .addListener(new ConsoleGameEventListener())
                .build();

        Player winner = game.play();
        System.out.println("Final positions: " + game.getPlayers() + " | winner = " + winner.getName());
    }

    /** Extensions 6.1 + 6.2: two dice, 150-cell board, random (seeded) placement. */
    private static void extendedGame() {
        banner("Extended game: 150 cells, 2 dice, random placement (seed 42)");

        Game game = Game.builder()
                .boardSize(150)
                .placementStrategy(new RandomPlacementStrategy(10, 8, 42L))
                .dice(new StandardDice(2, 6))
                .overshootPolicy(OvershootPolicy.BOUNCE_BACK) // min roll is 2, STAY could deadlock on cell 149
                .addPlayer("Dev")
                .addPlayer("Priya")
                .addListener(new ConsoleGameEventListener())
                .build();

        System.out.println("Board: " + game.getBoard().getEntities());
        game.play();
    }

    private static void banner(String title) {
        System.out.println();
        System.out.println("=".repeat(70));
        System.out.println(" " + title);
        System.out.println("=".repeat(70));
    }
}
