package com.lld.games.minesweeper.board;

import com.lld.games.minesweeper.exception.InvalidMoveException;
import com.lld.games.minesweeper.model.Cell;
import com.lld.games.minesweeper.model.Position;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * The grid of cells. Knows geometry (bounds, neighbours), mine layout and the flood-fill reveal.
 * It does NOT know about winning or losing; that is the Game's job.
 */
public class Board {

    private static final int[][] DIRECTIONS = {
            {-1, -1}, {-1, 0}, {-1, 1},
            {0, -1},           {0, 1},
            {1, -1},  {1, 0},  {1, 1}
    };

    private final int rows;
    private final int cols;
    private final Cell[][] grid;
    private int mineCount;
    private boolean minesPlaced;
    private int revealedSafeCells;

    public Board(int rows, int cols) {
        if (rows < 1 || cols < 1) {
            throw new IllegalArgumentException("Board must be at least 1x1");
        }
        this.rows = rows;
        this.cols = cols;
        this.grid = new Cell[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                grid[r][c] = new Cell(new Position(r, c));
            }
        }
    }

    /** Places mines once and pre-computes every cell's neighbour count: O(rows * cols). */
    public void placeMines(Set<Position> mines) {
        if (minesPlaced) {
            throw new IllegalStateException("Mines have already been placed");
        }
        for (Position p : mines) {
            getCell(p).setMine(true);
        }
        for (Cell[] row : grid) {
            for (Cell cell : row) {
                int count = 0;
                for (Position n : neighbours(cell.getPosition())) {
                    if (getCell(n).isMine()) {
                        count++;
                    }
                }
                cell.setAdjacentMines(count);
            }
        }
        this.mineCount = mines.size();
        this.minesPlaced = true;
    }

    /**
     * Reveals {@code start}; if it has zero adjacent mines, keeps opening outwards (flood fill).
     * Iterative BFS rather than recursion, so a 1000x1000 empty board cannot overflow the stack.
     * Flagged cells are never opened.
     *
     * @return every position revealed by this call, in BFS order (empty if nothing changed)
     */
    public List<Position> revealFrom(Position start) {
        List<Position> revealed = new ArrayList<>();
        Deque<Position> queue = new ArrayDeque<>();

        if (revealOne(start)) {
            revealed.add(start);
            queue.add(start);
        }
        while (!queue.isEmpty()) {
            Position current = queue.poll();
            Cell cell = getCell(current);
            if (cell.isMine() || cell.getAdjacentMines() > 0) {
                continue; // numbers form the border of an opened region
            }
            for (Position n : neighbours(current)) {
                if (revealOne(n)) {
                    revealed.add(n);
                    queue.add(n);
                }
            }
        }
        return revealed;
    }

    private boolean revealOne(Position p) {
        Cell cell = getCell(p);
        if (!cell.reveal()) {
            return false;
        }
        if (!cell.isMine()) {
            revealedSafeCells++;
        }
        return true;
    }

    /** Shows every mine (used when the game is lost). Does not touch the safe-cell counter. */
    public void revealAllMines() {
        for (Cell[] row : grid) {
            for (Cell cell : row) {
                if (cell.isMine() && cell.isHidden()) {
                    cell.reveal();
                }
            }
        }
    }

    public List<Position> neighbours(Position p) {
        List<Position> result = new ArrayList<>(8);
        for (int[] d : DIRECTIONS) {
            int r = p.row() + d[0];
            int c = p.col() + d[1];
            if (r >= 0 && r < rows && c >= 0 && c < cols) {
                result.add(new Position(r, c));
            }
        }
        return result;
    }

    public boolean isInBounds(Position p) {
        return p.row() >= 0 && p.row() < rows && p.col() >= 0 && p.col() < cols;
    }

    public Cell getCell(Position p) {
        if (!isInBounds(p)) {
            throw new InvalidMoveException("Position " + p + " is outside the " + rows + "x" + cols + " board");
        }
        return grid[p.row()][p.col()];
    }

    /** True once every non-mine cell is revealed: the only win condition. */
    public boolean allSafeCellsRevealed() {
        return minesPlaced && revealedSafeCells == rows * cols - mineCount;
    }

    public int getRows() {
        return rows;
    }

    public int getCols() {
        return cols;
    }

    public int getMineCount() {
        return mineCount;
    }

    public boolean isMinesPlaced() {
        return minesPlaced;
    }

    public int getRevealedSafeCells() {
        return revealedSafeCells;
    }
}
