package com.lld.games.minesweeper.render;

import com.lld.games.minesweeper.board.Board;
import com.lld.games.minesweeper.model.Cell;
import com.lld.games.minesweeper.model.Position;

/**
 * Turns a Board into text. Kept out of Board/Cell so the model has no idea how it is displayed;
 * a Swing or web renderer would be a sibling class.
 *
 * <pre>
 *  #  hidden     F  flag      .  empty (0)
 *  1-8 count     *  mine      X  the mine that exploded
 * </pre>
 */
public class ConsoleBoardRenderer {

    public String render(Board board, Position explodedAt) {
        StringBuilder sb = new StringBuilder();
        int width = String.valueOf(Math.max(board.getRows(), board.getCols()) - 1).length();
        String cellFormat = "%" + (width + 1) + "s";

        sb.append(" ".repeat(width));
        for (int c = 0; c < board.getCols(); c++) {
            sb.append(String.format(cellFormat, c));
        }
        sb.append('\n');

        for (int r = 0; r < board.getRows(); r++) {
            sb.append(String.format("%" + width + "d", r));
            for (int c = 0; c < board.getCols(); c++) {
                Position p = new Position(r, c);
                sb.append(String.format(cellFormat, symbol(board.getCell(p), p.equals(explodedAt))));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private char symbol(Cell cell, boolean exploded) {
        if (cell.isFlagged()) {
            return 'F';
        }
        if (cell.isHidden()) {
            return '#';
        }
        if (cell.isMine()) {
            return exploded ? 'X' : '*';
        }
        return cell.getAdjacentMines() == 0 ? '.' : (char) ('0' + cell.getAdjacentMines());
    }
}
