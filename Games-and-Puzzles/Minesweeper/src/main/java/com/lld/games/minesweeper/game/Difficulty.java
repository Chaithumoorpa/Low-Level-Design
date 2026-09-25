package com.lld.games.minesweeper.game;

/** The three classic presets. Custom sizes go through {@code Game.Builder#custom}. */
public enum Difficulty {
    BEGINNER(9, 9, 10),
    INTERMEDIATE(16, 16, 40),
    EXPERT(16, 30, 99);

    private final int rows;
    private final int cols;
    private final int mines;

    Difficulty(int rows, int cols, int mines) {
        this.rows = rows;
        this.cols = cols;
        this.mines = mines;
    }

    public int getRows() {
        return rows;
    }

    public int getCols() {
        return cols;
    }

    public int getMines() {
        return mines;
    }
}
