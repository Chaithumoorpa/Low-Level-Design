package com.lld.games.sudoku;

import com.lld.games.sudoku.board.Board;
import com.lld.games.sudoku.exception.InvalidMoveException;
import com.lld.games.sudoku.game.Game;
import com.lld.games.sudoku.game.GameStatus;
import com.lld.games.sudoku.generator.PuzzleGenerator;
import com.lld.games.sudoku.listener.GameEventListener;
import com.lld.games.sudoku.model.Difficulty;
import com.lld.games.sudoku.model.Position;
import com.lld.games.sudoku.render.ConsoleBoardRenderer;

import java.util.Scanner;
import java.util.Set;

/**
 * Console Sudoku. Rows and columns are 1-based on screen.
 *
 * <pre>
 *   java ... SudokuApp [EASY|MEDIUM|HARD] [seed]
 *
 *   r c v      place value v at row r, column c      e.g. "5 3 7"
 *   x r c      clear a cell
 *   n r c v    toggle pencil mark v
 *   notes r c  show pencil marks of a cell
 *   hint       fill one correct cell
 *   undo / redo
 *   check      list conflicting cells
 *   q          quit
 * </pre>
 */
public class SudokuApp {

    public static void main(String[] args) {
        Difficulty difficulty = args.length > 0 ? Difficulty.valueOf(args[0].toUpperCase()) : Difficulty.EASY;
        PuzzleGenerator generator = args.length > 1
                ? new PuzzleGenerator(Long.parseLong(args[1]))
                : new PuzzleGenerator();

        Board puzzle = generator.generate(difficulty);
        Game game = new Game(puzzle);
        game.addListener(new GameEventListener() {
            @Override
            public void onSolved(int movesMade, int hintsUsed) {
                System.out.println("*** Solved in " + movesMade + " moves with " + hintsUsed + " hint(s)! ***");
            }
        });

        ConsoleBoardRenderer renderer = new ConsoleBoardRenderer();
        Scanner in = new Scanner(System.in);
        System.out.println("Sudoku " + difficulty + " (" + puzzle.givenCount() + " clues)");
        System.out.println("Commands: r c v | x r c | n r c v | notes r c | hint | undo | redo | check | q");

        while (true) {
            System.out.println();
            System.out.print(renderer.render(game.getBoard()));
            if (game.getStatus() == GameStatus.SOLVED) {
                break;
            }
            System.out.print("> ");
            if (!in.hasNextLine()) {
                break;
            }
            String[] p = in.nextLine().trim().toLowerCase().split("\\s+");
            try {
                switch (p[0]) {
                    case "q" -> {
                        return;
                    }
                    case "hint" -> game.hint().ifPresent(pos ->
                            System.out.println("Hint: " + pos + " = " + game.getBoard().get(pos.row(), pos.col())));
                    case "undo" -> System.out.println(game.undo() ? "Undone" : "Nothing to undo");
                    case "redo" -> System.out.println(game.redo() ? "Redone" : "Nothing to redo");
                    case "check" -> {
                        Set<Position> conflicts = game.getConflicts();
                        System.out.println(conflicts.isEmpty() ? "No conflicts" : "Conflicts at " + conflicts);
                    }
                    case "x" -> game.clear(num(p, 1) - 1, num(p, 2) - 1);
                    case "n" -> game.toggleNote(num(p, 1) - 1, num(p, 2) - 1, num(p, 3));
                    case "notes" -> System.out.println("Notes: "
                            + game.getBoard().getNotes(num(p, 1) - 1, num(p, 2) - 1));
                    default -> game.place(num(p, 0) - 1, num(p, 1) - 1, num(p, 2));
                }
            } catch (InvalidMoveException | IllegalArgumentException e) {
                System.out.println("! " + e.getMessage());
            }
        }
    }

    private static int num(String[] parts, int index) {
        if (index >= parts.length) {
            throw new IllegalArgumentException("Missing number. Type e.g. \"5 3 7\" to put 7 at row 5, column 3");
        }
        return Integer.parseInt(parts[index]);
    }
}
