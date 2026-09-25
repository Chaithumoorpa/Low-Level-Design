package com.lld.games.minesweeper;

import com.lld.games.minesweeper.board.placement.RandomMinePlacementStrategy;
import com.lld.games.minesweeper.exception.InvalidMoveException;
import com.lld.games.minesweeper.game.Difficulty;
import com.lld.games.minesweeper.game.Game;
import com.lld.games.minesweeper.listener.GameEventListener;
import com.lld.games.minesweeper.model.Position;
import com.lld.games.minesweeper.render.ConsoleBoardRenderer;

import java.util.List;
import java.util.Scanner;

/**
 * Interactive console game.
 *
 * <pre>
 *   java ... MinesweeperApp [BEGINNER|INTERMEDIATE|EXPERT] [seed]
 *
 *   r <row> <col>   reveal
 *   f <row> <col>   flag / unflag
 *   c <row> <col>   chord (open neighbours of a satisfied number)
 *   q               quit
 * </pre>
 */
public class MinesweeperApp {

    public static void main(String[] args) {
        Difficulty difficulty = args.length > 0 ? Difficulty.valueOf(args[0].toUpperCase()) : Difficulty.BEGINNER;
        RandomMinePlacementStrategy strategy = args.length > 1
                ? new RandomMinePlacementStrategy(Long.parseLong(args[1]))
                : new RandomMinePlacementStrategy();

        Game game = Game.builder()
                .difficulty(difficulty)
                .placementStrategy(strategy)
                .addListener(new GameEventListener() {
                    @Override
                    public void onCellsRevealed(List<Position> positions) {
                        System.out.println("Opened " + positions.size() + " cell(s)");
                    }

                    @Override
                    public void onGameWon() {
                        System.out.println("*** You cleared the field. You win! ***");
                    }

                    @Override
                    public void onGameLost(Position explodedAt) {
                        System.out.println("*** BOOM at " + explodedAt + ". Game over. ***");
                    }
                })
                .build();

        ConsoleBoardRenderer renderer = new ConsoleBoardRenderer();
        Scanner in = new Scanner(System.in);

        System.out.println("Minesweeper " + difficulty + " (" + difficulty.getRows() + "x"
                + difficulty.getCols() + ", " + difficulty.getMines() + " mines)");
        System.out.println("Commands: r <row> <col> | f <row> <col> | c <row> <col> | q");

        while (!game.getStatus().isOver()) {
            System.out.println();
            System.out.print(renderer.render(game.getBoard(), null));
            System.out.print("Mines left: " + game.getRemainingFlags() + " > ");
            if (!in.hasNextLine()) {
                break;
            }
            String[] parts = in.nextLine().trim().split("\\s+");
            if (parts[0].equalsIgnoreCase("q")) {
                break;
            }
            try {
                if (parts.length != 3) {
                    throw new IllegalArgumentException("Expected: <r|f|c> <row> <col>");
                }
                int row = Integer.parseInt(parts[1]);
                int col = Integer.parseInt(parts[2]);
                switch (parts[0].toLowerCase()) {
                    case "r" -> game.reveal(row, col);
                    case "f" -> game.toggleFlag(row, col);
                    case "c" -> game.chord(row, col);
                    default -> throw new IllegalArgumentException("Unknown command " + parts[0]);
                }
            } catch (InvalidMoveException | IllegalArgumentException e) {
                System.out.println("! " + e.getMessage());
            }
        }

        System.out.println();
        System.out.print(renderer.render(game.getBoard(), game.getExplodedAt().orElse(null)));
        System.out.println("Final status: " + game.getStatus());
    }
}
