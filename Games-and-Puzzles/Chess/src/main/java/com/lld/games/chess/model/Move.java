package com.lld.games.chess.model;

import com.lld.games.chess.piece.Piece;

/**
 * An immutable move. Stores everything needed to apply it and to describe it afterwards.
 *
 * @param captured  the piece taken, or null (for en passant, the pawn beside the destination)
 * @param promotion the piece a pawn becomes, or null
 */
public record Move(Position from, Position to, Piece piece, Piece captured, MoveType type, PieceType promotion) {

    public static Move of(Position from, Position to, Piece piece, Piece captured) {
        return new Move(from, to, piece, captured, MoveType.NORMAL, null);
    }

    public boolean isCapture() {
        return captured != null;
    }

    public boolean isCastle() {
        return type == MoveType.CASTLE_KINGSIDE || type == MoveType.CASTLE_QUEENSIDE;
    }

    /** UCI notation used by engines and by the console app: e2e4, e7e8q. */
    public String toUci() {
        return from.toString() + to + (promotion == null ? "" : String.valueOf(Character.toLowerCase(promotion.symbol())));
    }

    /** Long algebraic notation for humans: Ng1-f3, e5xd6, O-O, e7-e8=Q. */
    @Override
    public String toString() {
        if (type == MoveType.CASTLE_KINGSIDE) {
            return "O-O";
        }
        if (type == MoveType.CASTLE_QUEENSIDE) {
            return "O-O-O";
        }
        String prefix = piece.getType() == PieceType.PAWN ? "" : String.valueOf(piece.getType().symbol());
        String text = prefix + from + (isCapture() ? "x" : "-") + to;
        if (promotion != null) {
            text += "=" + promotion.symbol();
        }
        if (type == MoveType.EN_PASSANT) {
            text += " e.p.";
        }
        return text;
    }
}
