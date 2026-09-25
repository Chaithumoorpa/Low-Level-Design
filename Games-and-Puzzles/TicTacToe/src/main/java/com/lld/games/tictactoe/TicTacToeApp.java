package com.lld.games.tictactoe;

import com.lld.games.tictactoe.exception.InvalidMoveException;
import com.lld.games.tictactoe.game.Game;
import com.lld.games.tictactoe.game.GameStatus;
import com.lld.games.tictactoe.listener.GameEventListener;
import com.lld.games.tictactoe.model.Player;
import com.lld.games.tictactoe.render.ConsoleBoardRenderer;

import java.util.Scanner;

/**
 * Two-player console game.
 *
 * <pre>
 *   java ... TicTacToeApp [boardSize]      (3..10, default 3)
 *   &lt;row&gt; &lt;col&gt;   place your mark, e.g. "1 1" for the centre of a 3x3 board
 *   q             quit
 * </pre>
 */
public class TicTacToeApp {

    public static void main(String[] args) {
        int size = args.length > 0 ? Integer.parseInt(args[0]) : 3;

        Game game = Game.builder()
                .boardSize(size)
                .addPlayer("Player 1")
                .addPlayer("Player 2")
                .addListener(new GameEventListener() {
                    @Override
                    public void onGameOver(GameStatus status, Player winner) {
                        System.out.println(winner != null
                                ? "*** " + winner + " wins! ***"
                                : "*** It's a draw! ***");
                    }
                })
                .build();

        ConsoleBoardRenderer renderer = new ConsoleBoardRenderer();
        Scanner in = new Scanner(System.in);
        System.out.println("Tic Tac Toe " + size + "x" + size + ". Enter moves as: <row> <col>   (q to quit)");

        while (true) {
            System.out.println();
            System.out.print(renderer.render(game.getBoard()));
            if (game.getStatus().isOver()) {
                break;
            }
            System.out.print(game.getCurrentPlayer() + " > ");
            if (!in.hasNextLine()) {
                break;
            }
            String line = in.nextLine().trim();
            if (line.equalsIgnoreCase("q")) {
                break;
            }
            try {
                String[] parts = line.split("\\s+");
                if (parts.length != 2) {
                    throw new IllegalArgumentException("Enter two numbers: <row> <col>");
                }
                game.makeMove(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            } catch (InvalidMoveException | IllegalArgumentException e) {
                System.out.println("! " + e.getMessage());
            }
        }
    }
}
