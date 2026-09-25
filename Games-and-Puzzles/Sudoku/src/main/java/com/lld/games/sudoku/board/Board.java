package com.lld.games.sudoku.board;

import com.lld.games.sudoku.exception.InvalidMoveException;
import com.lld.games.sudoku.model.Position;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * A Sudoku grid of size N = boxRows x boxCols (9x9 with 3x3 boxes, 6x6 with 2x3 boxes, 4x4 ...).
 * Values are 1..N, 0 means empty.
 *
 * <p>Besides the values, the board keeps, for every row, column and box, how many times each
 * digit appears. That makes "does 7 clash here?" an O(1) question and lets the board also hold
 * deliberately conflicting entries (lenient mode) while still reporting them.
 *
 * <p>The board knows the rules of the grid. It does not know about turns, undo, or hints.
 */
public class Board {

    private final int boxRows;
    private final int boxCols;
    private final int size;
    private final int[][] values;
    private final boolean[][] given;
    private final int[][] notes;          // bit d set = pencil mark d
    private final int[][] rowCount;       // [row][digit]
    private final int[][] colCount;       // [col][digit]
    private final int[][] boxCount;       // [box][digit]
    private int filled;

    public Board(int boxRows, int boxCols) {
        if (boxRows < 1 || boxCols < 1 || boxRows * boxCols < 2 || boxRows * boxCols > 16) {
            throw new IllegalArgumentException("Box must be between 1x2 and 4x4, got " + boxRows + "x" + boxCols);
        }
        this.boxRows = boxRows;
        this.boxCols = boxCols;
        this.size = boxRows * boxCols;
        this.values = new int[size][size];
        this.given = new boolean[size][size];
        this.notes = new int[size][size];
        this.rowCount = new int[size][size + 1];
        this.colCount = new int[size][size + 1];
        this.boxCount = new int[size][size + 1];
    }

    /** Standard 9x9 board. */
    public static Board classic() {
        return new Board(3, 3);
    }

    /**
     * Parses a puzzle string read row by row: digits 1-9 (or letters A-G for 10-16),
     * and '.' or '0' for an empty cell. Whitespace and '|' / '-' / '+' separators are ignored.
     * Every filled cell becomes a given clue.
     */
    public static Board parse(String text, int boxRows, int boxCols) {
        Board board = new Board(boxRows, boxCols);
        int n = board.size;
        String cells = text.replaceAll("[\\s|+\\-]", "");
        if (cells.length() != n * n) {
            throw new IllegalArgumentException("Expected " + (n * n) + " cells, got " + cells.length());
        }
        for (int i = 0; i < cells.length(); i++) {
            char ch = cells.charAt(i);
            if (ch == '.' || ch == '0') {
                continue;
            }
            int value = Character.digit(ch, 36);   // '1'..'9' -> 1..9, 'A'..'G' -> 10..16
            if (value < 1 || value > n) {
                throw new IllegalArgumentException("Invalid value '" + ch + "' for a " + n + "x" + n + " board");
            }
            board.setGiven(i / n, i % n, value);
        }
        return board;
    }

    public static Board parse(String text) {
        return parse(text, 3, 3);
    }

    // ------------------------------------------------------------------ geometry

    public int getSize() {
        return size;
    }

    public int getBoxRows() {
        return boxRows;
    }

    public int getBoxCols() {
        return boxCols;
    }

    public int boxIndex(int row, int col) {
        return (row / boxRows) * boxRows + (col / boxCols);
    }

    public boolean inBounds(int row, int col) {
        return row >= 0 && row < size && col >= 0 && col < size;
    }

    // ------------------------------------------------------------------ values

    public int get(int row, int col) {
        check(row, col);
        return values[row][col];
    }

    public boolean isEmpty(int row, int col) {
        return get(row, col) == 0;
    }

    public boolean isGiven(int row, int col) {
        check(row, col);
        return given[row][col];
    }

    /**
     * Writes a value (0 clears). Refuses to touch given clues. Does not reject conflicts:
     * whether a conflicting entry is allowed is a game policy, decided by the caller.
     */
    public void set(int row, int col, int value) {
        check(row, col);
        if (given[row][col]) {
            throw new InvalidMoveException("Cell " + Position.of(row, col) + " is a given clue and cannot be changed");
        }
        if (value < 0 || value > size) {
            throw new InvalidMoveException("Value must be 1.." + size + " (or 0 to clear), got " + value);
        }
        write(row, col, value);
    }

    private void setGiven(int row, int col, int value) {
        write(row, col, value);
        given[row][col] = true;
    }

    private void write(int row, int col, int value) {
        int old = values[row][col];
        if (old == value) {
            return;
        }
        int box = boxIndex(row, col);
        if (old != 0) {
            rowCount[row][old]--;
            colCount[col][old]--;
            boxCount[box][old]--;
            filled--;
        }
        if (value != 0) {
            rowCount[row][value]++;
            colCount[col][value]++;
            boxCount[box][value]++;
            filled++;
        }
        values[row][col] = value;
    }

    // ------------------------------------------------------------------ rules

    /** Would {@code value} at (row, col) clash with another cell in its row, column or box? O(1). */
    public boolean conflicts(int row, int col, int value) {
        check(row, col);
        if (value == 0) {
            return false;
        }
        int self = values[row][col] == value ? 1 : 0;   // don't count the cell against itself
        return rowCount[row][value] - self > 0
                || colCount[col][value] - self > 0
                || boxCount[boxIndex(row, col)][value] - self > 0;
    }

    /** Human-readable reason for a conflict, or null if there is none. */
    public String conflictReason(int row, int col, int value) {
        int self = values[row][col] == value ? 1 : 0;
        if (rowCount[row][value] - self > 0) {
            return value + " is already in row " + (row + 1);
        }
        if (colCount[col][value] - self > 0) {
            return value + " is already in column " + (col + 1);
        }
        if (boxCount[boxIndex(row, col)][value] - self > 0) {
            return value + " is already in this box";
        }
        return null;
    }

    /** Every filled cell whose value appears more than once in its row, column or box. */
    public Set<Position> conflictingCells() {
        Set<Position> result = new LinkedHashSet<>();
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                int v = values[r][c];
                if (v != 0 && (rowCount[r][v] > 1 || colCount[c][v] > 1 || boxCount[boxIndex(r, c)][v] > 1)) {
                    result.add(Position.of(r, c));
                }
            }
        }
        return result;
    }

    /** Digits that can legally go into (row, col) right now, as a bit mask (bit d = digit d). */
    public int candidateMask(int row, int col) {
        int mask = 0;
        for (int d = 1; d <= size; d++) {
            if (!conflicts(row, col, d)) {
                mask |= 1 << d;
            }
        }
        return mask;
    }

    public boolean isFull() {
        return filled == size * size;
    }

    /** Solved = every cell filled and no digit repeated in any row, column or box. */
    public boolean isSolved() {
        return isFull() && conflictingCells().isEmpty();
    }

    public int filledCount() {
        return filled;
    }

    public int givenCount() {
        int count = 0;
        for (boolean[] row : given) {
            for (boolean g : row) {
                if (g) {
                    count++;
                }
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ pencil marks

    public Set<Integer> getNotes(int row, int col) {
        check(row, col);
        Set<Integer> result = new TreeSet<>();
        for (int d = 1; d <= size; d++) {
            if ((notes[row][col] & (1 << d)) != 0) {
                result.add(d);
            }
        }
        return result;
    }

    public int getNotesMask(int row, int col) {
        check(row, col);
        return notes[row][col];
    }

    public void setNotesMask(int row, int col, int mask) {
        check(row, col);
        notes[row][col] = mask;
    }

    // ------------------------------------------------------------------ copies

    /** Snapshot of the values, for solvers (which work on raw arrays for speed). */
    public int[][] toGrid() {
        int[][] copy = new int[size][];
        for (int r = 0; r < size; r++) {
            copy[r] = values[r].clone();
        }
        return copy;
    }

    /** Builds a board from raw values; non-zero cells become givens if {@code asGivens}, else player entries. */
    public static Board fromGrid(int[][] grid, int boxRows, int boxCols, boolean asGivens) {
        Board board = new Board(boxRows, boxCols);
        for (int r = 0; r < grid.length; r++) {
            for (int c = 0; c < grid.length; c++) {
                if (grid[r][c] != 0) {
                    if (asGivens) {
                        board.setGiven(r, c, grid[r][c]);
                    } else {
                        board.write(r, c, grid[r][c]);
                    }
                }
            }
        }
        return board;
    }

    /** Compact one-line form ('.' for empty), the same format {@link #parse} accepts. */
    public String toLine() {
        StringBuilder sb = new StringBuilder(size * size);
        for (int[] row : values) {
            for (int v : row) {
                sb.append(v == 0 ? '.' : Character.toUpperCase(Character.forDigit(v, 36)));
            }
        }
        return sb.toString();
    }

    private void check(int row, int col) {
        if (!inBounds(row, col)) {
            throw new InvalidMoveException("Cell (" + (row + 1) + ", " + (col + 1) + ") is outside the "
                    + size + "x" + size + " board");
        }
    }
}
