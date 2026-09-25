package com.lld.games.tictactoe.render;

import com.lld.games.tictactoe.board.Board;
import com.lld.games.tictactoe.model.Symbol;

/**
 * Draws the board as text with row/column numbers. Kept out of Board so the model has no
 * display code; a GUI or web view would be a sibling renderer.
 */
public class ConsoleBoardRenderer {

    public String render(Board board) {
        int n = board.getSize();
        StringBuilder sb = new StringBuilder("    ");
        for (int c = 0; c < n; c++) {
            sb.append(c).append(c < n - 1 ? "   " : "\n");
        }
        for (int r = 0; r < n; r++) {
            StringBuilder line = new StringBuilder().append(r).append("  ");
            for (int c = 0; c < n; c++) {
                Symbol s = board.get(r, c);
                line.append(' ').append(s == null ? ' ' : s.name().charAt(0)).append(' ');
                if (c < n - 1) {
                    line.append('|');
                }
            }
            sb.append(line.toString().stripTrailing()).append('\n');
            if (r < n - 1) {
                sb.append("   ").append("-".repeat(n * 4 - 1)).append('\n');
            }
        }
        return sb.toString();
    }
}
