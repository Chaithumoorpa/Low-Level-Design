package com.lld.games.tictactoe.board;

import com.lld.games.tictactoe.exception.InvalidMoveException;
import com.lld.games.tictactoe.model.Symbol;

/**
 * An N x N grid of symbols ({@code null} = empty). Knows bounds and occupancy only;
 * it has no idea whose turn it is or who won.
 */
public class Board {

    public static final int MIN_SIZE = 3;
    public static final int MAX_SIZE = 10;

    private final int size;
    private final Symbol[][] grid;
    private int filledCells;

    public Board(int size) {
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw new IllegalArgumentException(
                    "Board size must be between " + MIN_SIZE + " and " + MAX_SIZE + ", got " + size);
        }
        this.size = size;
        this.grid = new Symbol[size][size];
    }

    public void place(int row, int col, Symbol symbol) {
        validatePosition(row, col);
        if (grid[row][col] != null) {
            throw new InvalidMoveException("Cell (" + row + ", " + col + ") is already occupied");
        }
        grid[row][col] = symbol;
        filledCells++;
    }

    public Symbol get(int row, int col) {
        validatePosition(row, col);
        return grid[row][col];
    }

    public boolean isEmpty(int row, int col) {
        return get(row, col) == null;
    }

    /** O(1): a counter instead of scanning all N*N cells after every move. */
    public boolean isFull() {
        return filledCells == size * size;
    }

    public int getSize() {
        return size;
    }

    private void validatePosition(int row, int col) {
        if (row < 0 || row >= size || col < 0 || col >= size) {
            throw new InvalidMoveException("Position (" + row + ", " + col + ") is out of bounds");
        }
    }
}
