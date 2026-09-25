package com.lld.games.sudoku.render;

import com.lld.games.sudoku.board.Board;

/**
 * Draws the grid with 1-based row/column labels and box borders. Empty cells are '.'.
 * Works for any box shape (4x4, 6x6, 9x9, ...).
 */
public class ConsoleBoardRenderer {

    public String render(Board board) {
        int n = board.getSize();
        int boxCols = board.getBoxCols();
        int boxRows = board.getBoxRows();
        StringBuilder sb = new StringBuilder();

        StringBuilder header = new StringBuilder("    ");
        StringBuilder border = new StringBuilder("   +");
        for (int c = 0; c < n; c++) {
            header.append(c + 1 < 10 ? " " : "").append(c + 1);
            border.append("--");
            if ((c + 1) % boxCols == 0) {
                header.append(c + 1 < n ? "  " : "");
                border.append("-+");
            }
        }
        sb.append(header.toString().stripTrailing()).append('\n').append(border).append('\n');

        for (int r = 0; r < n; r++) {
            sb.append(String.format("%2d |", r + 1));
            for (int c = 0; c < n; c++) {
                int v = board.get(r, c);
                sb.append(' ').append(v == 0 ? '.' : Character.toUpperCase(Character.forDigit(v, 36)));
                if ((c + 1) % boxCols == 0) {
                    sb.append(" |");
                }
            }
            sb.append('\n');
            if ((r + 1) % boxRows == 0) {
                sb.append(border).append('\n');
            }
        }
        return sb.toString();
    }
}
