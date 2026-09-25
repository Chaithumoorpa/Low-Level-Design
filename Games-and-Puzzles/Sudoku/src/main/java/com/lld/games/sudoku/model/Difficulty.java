package com.lld.games.sudoku.model;

/**
 * Difficulty expressed as the share of cells given as clues. For 9x9 this is roughly
 * 40 / 32 / 26 clues. Fewer clues usually means a harder puzzle, although true difficulty
 * depends on which solving techniques are required (see README follow-ups).
 */
public enum Difficulty {
    EASY(0.50),
    MEDIUM(0.40),
    HARD(0.32);

    private final double clueRatio;

    Difficulty(double clueRatio) {
        this.clueRatio = clueRatio;
    }

    public int targetClues(int cellCount) {
        return (int) Math.round(cellCount * clueRatio);
    }
}
