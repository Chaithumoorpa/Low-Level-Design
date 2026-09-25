package com.lld.games.chess.model;

/** Side to move. Also carries the board geometry that differs per side (pawn direction, home ranks). */
public enum Color {
    WHITE(1, 0, 1, 7),
    BLACK(-1, 7, 6, 0);

    private final int pawnDirection;
    private final int homeRank;
    private final int pawnStartRank;
    private final int promotionRank;

    Color(int pawnDirection, int homeRank, int pawnStartRank, int promotionRank) {
        this.pawnDirection = pawnDirection;
        this.homeRank = homeRank;
        this.pawnStartRank = pawnStartRank;
        this.promotionRank = promotionRank;
    }

    public Color opposite() {
        return this == WHITE ? BLACK : WHITE;
    }

    public int pawnDirection() {
        return pawnDirection;
    }

    public int homeRank() {
        return homeRank;
    }

    public int pawnStartRank() {
        return pawnStartRank;
    }

    public int promotionRank() {
        return promotionRank;
    }

    public String displayName() {
        return this == WHITE ? "White" : "Black";
    }
}
