package com.game.tic_tac_toe.core;


import com.game.tic_tac_toe.enums.Symbol;
import com.game.tic_tac_toe.exceptions.InvalidMoveException;
import com.game.tic_tac_toe.model.Cell;

public class Board {
    private final Cell[][] grid;
    private final int size;

    public Board(int size) {
        this.size = size;
        this.grid = new Cell[size][size];
        initializeBoard();
    }

    private void initializeBoard() {
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                grid[i][j] = new Cell();
            }
        }
    }

    public void placeSymbol(int row, int col, Symbol symbol) {
        validatePosition(row, col);
        grid[row][col].setSymbol(symbol);
    }

    public boolean isCellEmpty(int row, int col) {
        validatePosition(row, col);
        return grid[row][col].isEmpty();
    }

    public boolean isFull() {
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                if (grid[i][j].isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    public Cell getCell(int row, int col) {
        validatePosition(row, col);
        return grid[row][col];
    }

    public int getSize() {
        return size;
    }
    
    

    public Cell[][] getGrid() {
		return grid;
	}

	private void validatePosition(int row, int col) {
        if (row < 0 || row >= size || col < 0 || col >= size) {
            throw new InvalidMoveException(
                "Position (" + row + ", " + col + ") is out of bounds"
            );
        }
    }

    public void printBoard() {
        System.out.println();
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                System.out.print(" " + grid[i][j].getSymbol().getDisplayChar() + " ");
                if (j < size - 1) System.out.print("|");
            }
            System.out.println();
            if (i < size - 1) {
                System.out.println("-".repeat(size * 4 - 1));
            }
        }
        System.out.println();
    }
}