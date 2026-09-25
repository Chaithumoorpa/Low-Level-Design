package com.lld.games.chess;

import com.lld.games.chess.exception.InvalidMoveException;
import com.lld.games.chess.game.EndReason;
import com.lld.games.chess.game.Game;
import com.lld.games.chess.game.GameStatus;
import com.lld.games.chess.listener.GameEventListener;
import com.lld.games.chess.model.Color;
import com.lld.games.chess.model.Move;
import com.lld.games.chess.model.Position;
import com.lld.games.chess.render.ConsoleBoardRenderer;

import java.util.List;
import java.util.Scanner;
import java.util.stream.Collectors;

/**
 * Two-player console chess.
 *
 * <pre>
 *   e2e4 / e7e8n   make a move (UCI; promotion letter optional, queen by default)
 *   moves e2       list legal moves of the piece on e2
 *   undo           take back the last move
 *   resign | draw  resign, or record an agreed draw
 *   fen            print the position as FEN
 *   quit
 * </pre>
 */
public class ChessApp {

    public static void main(String[] args) {
        Game game = new Game("White", "Black");
        game.addListener(new GameEventListener() {
            @Override
            public void onMove(Move move) {
                System.out.println("Played " + move);
            }

            @Override
            public void onCheck(Color colorInCheck) {
                System.out.println(colorInCheck.displayName() + " is in CHECK!");
            }

            @Override
            public void onGameOver(GameStatus status, EndReason reason) {
                System.out.println("*** Game over: " + status + " by " + reason + " ***");
            }
        });

        ConsoleBoardRenderer renderer = new ConsoleBoardRenderer();
        Scanner in = new Scanner(System.in);
        System.out.println("Console Chess. Moves like e2e4; also: moves e2 | undo | resign | draw | fen | quit");

        while (true) {
            System.out.println();
            System.out.print(renderer.render(game.getBoard()));
            if (game.getStatus().isOver()) {
                break;
            }
            System.out.print(game.getTurn().displayName() + " to move > ");
            if (!in.hasNextLine()) {
                break;
            }
            String line = in.nextLine().trim();
            try {
                if (line.equalsIgnoreCase("quit")) {
                    break;
                } else if (line.equalsIgnoreCase("undo")) {
                    System.out.println("Took back " + game.undo());
                } else if (line.equalsIgnoreCase("resign")) {
                    game.resign(game.getTurn());
                } else if (line.equalsIgnoreCase("draw")) {
                    game.agreeDraw();
                } else if (line.equalsIgnoreCase("fen")) {
                    System.out.println(game.toFen());
                } else if (line.toLowerCase().startsWith("moves ")) {
                    List<Move> moves = game.getLegalMovesFrom(Position.of(line.substring(6).trim()));
                    System.out.println(moves.isEmpty() ? "No legal moves"
                            : moves.stream().map(Move::toString).collect(Collectors.joining(", ")));
                } else {
                    game.makeMove(line);
                }
            } catch (InvalidMoveException | IllegalArgumentException e) {
                System.out.println("! " + e.getMessage());
            }
        }

        System.out.println("Moves: " + game.getHistory().stream().map(Move::toString).collect(Collectors.joining(" ")));
    }
}
