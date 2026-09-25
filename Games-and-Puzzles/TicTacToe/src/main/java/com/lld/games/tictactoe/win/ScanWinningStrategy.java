package com.lld.games.tictactoe.win;

import com.lld.games.tictactoe.board.Board;
import com.lld.games.tictactoe.model.Move;
import com.lld.games.tictactoe.model.Symbol;

/**
 * Checks only the lines through the last move: its row, its column, and a diagonal only if the move
 * lies on it. O(N) per move, no extra memory. The natural first answer in an interview.
 */
public class ScanWinningStrategy implements WinningStrategy {

    @Override
    public boolean isWinningMove(Board board, Move move) {
        int n = board.getSize();
        int row = move.row();
        int col = move.col();
        Symbol s = move.symbol();

        return lineComplete(board, s, row, 0, 0, 1, n)                        // row
                || lineComplete(board, s, 0, col, 1, 0, n)                    // column
                || (row == col && lineComplete(board, s, 0, 0, 1, 1, n))      // main diagonal
                || (row + col == n - 1 && lineComplete(board, s, 0, n - 1, 1, -1, n)); // anti-diagonal
    }

    private boolean lineComplete(Board board, Symbol s, int r, int c, int dr, int dc, int n) {
        for (int i = 0; i < n; i++) {
            if (board.get(r + i * dr, c + i * dc) != s) {
                return false;
            }
        }
        return true;
    }
}
