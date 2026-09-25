package com.lld.games.chess.model;

/** The six kinds of chess piece, with their standard one-letter symbols (as used in FEN). */
public enum PieceType {
    KING('K'),
    QUEEN('Q'),
    ROOK('R'),
    BISHOP('B'),
    KNIGHT('N'),
    PAWN('P');

    private final char symbol;

    PieceType(char symbol) {
        this.symbol = symbol;
    }

    public char symbol() {
        return symbol;
    }

    /** Parses a symbol in either case: 'q' and 'Q' both give QUEEN. */
    public static PieceType fromSymbol(char c) {
        char upper = Character.toUpperCase(c);
        for (PieceType type : values()) {
            if (type.symbol == upper) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown piece symbol: " + c);
    }
}
