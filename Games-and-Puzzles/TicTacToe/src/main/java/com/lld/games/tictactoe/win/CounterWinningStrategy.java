package com.lld.games.tictactoe.win;

import com.lld.games.tictactoe.board.Board;
import com.lld.games.tictactoe.model.Move;
import com.lld.games.tictactoe.model.Symbol;

/**
 * O(1) per move: keep, for each symbol, how many marks it has in every row, every column and
 * both diagonals. A line is complete the moment one of those counters reaches N.
 *
 * <p>Memory is O(N) per symbol. This is the classic interview follow-up to the scanning approach.
 */
public class CounterWinningStrategy implements WinningStrategy {

    private int size = -1;
    private int[][] rowCounts;      // [symbol][row]
    private int[][] colCounts;      // [symbol][col]
    private int[] diagCounts;       // [symbol]
    private int[] antiDiagCounts;   // [symbol]

    @Override
    public boolean isWinningMove(Board board, Move move) {
        initIfNeeded(board.getSize());
        int s = move.symbol().ordinal();
        int row = move.row();
        int col = move.col();

        boolean win = ++rowCounts[s][row] == size;
        win |= ++colCounts[s][col] == size;
        if (row == col) {
            win |= ++diagCounts[s] == size;
        }
        if (row + col == size - 1) {
            win |= ++antiDiagCounts[s] == size;
        }
        return win;
    }

    private void initIfNeeded(int n) {
        if (size == n) {
            return;
        }
        if (size != -1) {
            throw new IllegalStateException("A CounterWinningStrategy instance belongs to one game");
        }
        int symbols = Symbol.values().length;
        size = n;
        rowCounts = new int[symbols][n];
        colCounts = new int[symbols][n];
        diagCounts = new int[symbols];
        antiDiagCounts = new int[symbols];
    }
}
