package com.lld.games.chess.model;

/**
 * The four castling rights. A right is lost forever once the king or that rook moves
 * (or the rook is captured). Whether castling is possible *right now* also depends on
 * empty and unattacked squares; that is checked when moves are generated.
 */
public enum CastlingRight {
    WHITE_KINGSIDE(Color.WHITE, true, 'K'),
    WHITE_QUEENSIDE(Color.WHITE, false, 'Q'),
    BLACK_KINGSIDE(Color.BLACK, true, 'k'),
    BLACK_QUEENSIDE(Color.BLACK, false, 'q');

    private final Color color;
    private final boolean kingside;
    private final char fenSymbol;

    CastlingRight(Color color, boolean kingside, char fenSymbol) {
        this.color = color;
        this.kingside = kingside;
        this.fenSymbol = fenSymbol;
    }

    public Color color() {
        return color;
    }

    public boolean kingside() {
        return kingside;
    }

    public char fenSymbol() {
        return fenSymbol;
    }

    /** Square the rook starts on for this right, e.g. h1 for WHITE_KINGSIDE. */
    public Position rookSquare() {
        return new Position(kingside ? 7 : 0, color.homeRank());
    }

    public static CastlingRight of(Color color, boolean kingside) {
        if (color == Color.WHITE) {
            return kingside ? WHITE_KINGSIDE : WHITE_QUEENSIDE;
        }
        return kingside ? BLACK_KINGSIDE : BLACK_QUEENSIDE;
    }
}
