package com.lld.games.chess.notation;

import com.lld.games.chess.board.Board;
import com.lld.games.chess.model.CastlingRight;
import com.lld.games.chess.model.Color;
import com.lld.games.chess.model.PieceType;
import com.lld.games.chess.model.Position;
import com.lld.games.chess.piece.Piece;
import com.lld.games.chess.piece.PieceFactory;

import java.util.EnumSet;
import java.util.Set;

/**
 * Forsyth-Edwards Notation: the standard one-line description of a chess position, e.g.
 * {@code rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1}.
 *
 * <p>Fields: placement, side to move, castling rights, en passant square, half-move clock,
 * full-move number. Used to set up the start position, load puzzles in tests, and detect
 * repeated positions.
 */
public final class Fen {

    public static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    /** Everything a FEN string describes. */
    public record FenPosition(Board board, Color sideToMove, int halfmoveClock, int fullmoveNumber) {
    }

    private Fen() {
    }

    public static FenPosition parse(String fen) {
        String[] parts = fen.trim().split("\\s+");
        if (parts.length < 4) {
            throw new IllegalArgumentException("FEN needs at least 4 fields: " + fen);
        }
        Board board = new Board();

        String[] ranks = parts[0].split("/");
        if (ranks.length != 8) {
            throw new IllegalArgumentException("FEN placement needs 8 ranks: " + parts[0]);
        }
        for (int i = 0; i < 8; i++) {
            int rank = 7 - i; // FEN lists rank 8 first
            int file = 0;
            for (char c : ranks[i].toCharArray()) {
                if (Character.isDigit(c)) {
                    file += c - '0';
                } else {
                    board.place(new Position(file, rank), PieceFactory.fromSymbol(c));
                    file++;
                }
            }
            if (file != 8) {
                throw new IllegalArgumentException("FEN rank " + (rank + 1) + " does not have 8 squares");
            }
        }

        Color side = switch (parts[1]) {
            case "w" -> Color.WHITE;
            case "b" -> Color.BLACK;
            default -> throw new IllegalArgumentException("Side to move must be w or b: " + parts[1]);
        };

        Set<CastlingRight> rights = EnumSet.noneOf(CastlingRight.class);
        if (!parts[2].equals("-")) {
            for (char c : parts[2].toCharArray()) {
                for (CastlingRight right : CastlingRight.values()) {
                    if (right.fenSymbol() == c) {
                        rights.add(right);
                    }
                }
            }
        }
        board.setCastlingRights(rights);
        board.setEnPassantTarget(parts[3].equals("-") ? null : Position.of(parts[3]));

        int halfmove = parts.length > 4 ? Integer.parseInt(parts[4]) : 0;
        int fullmove = parts.length > 5 ? Integer.parseInt(parts[5]) : 1;
        return new FenPosition(board, side, halfmove, fullmove);
    }

    public static String toFen(Board board, Color sideToMove, int halfmoveClock, int fullmoveNumber) {
        Position ep = board.getEnPassantTarget();
        return describe(board, sideToMove, ep) + " " + halfmoveClock + " " + fullmoveNumber;
    }

    /**
     * Identity of a position for the threefold-repetition rule: placement, side to move,
     * castling rights, and the en passant square ONLY if a pawn could actually capture there.
     * (After 1.e4 no black pawn can take on e3, so that position repeats a later one without it.)
     */
    public static String positionKey(Board board, Color sideToMove) {
        Position ep = board.getEnPassantTarget();
        if (ep != null && !pawnCanCaptureEnPassant(board, sideToMove, ep)) {
            ep = null;
        }
        return describe(board, sideToMove, ep);
    }

    private static boolean pawnCanCaptureEnPassant(Board board, Color side, Position ep) {
        int rank = ep.rank() - side.pawnDirection();
        for (int df : new int[]{-1, 1}) {
            int file = ep.file() + df;
            if (Position.inBounds(file, rank)) {
                Piece p = board.pieceAt(new Position(file, rank));
                if (p != null && p.getColor() == side && p.getType() == PieceType.PAWN) {
                    return true;
                }
            }
        }
        return false;
    }

    /** First four FEN fields. */
    private static String describe(Board board, Color sideToMove, Position ep) {
        StringBuilder sb = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--) {
            int empty = 0;
            for (int file = 0; file < 8; file++) {
                Piece piece = board.pieceAt(new Position(file, rank));
                if (piece == null) {
                    empty++;
                } else {
                    if (empty > 0) {
                        sb.append(empty);
                        empty = 0;
                    }
                    sb.append(piece.symbol());
                }
            }
            if (empty > 0) {
                sb.append(empty);
            }
            if (rank > 0) {
                sb.append('/');
            }
        }

        sb.append(sideToMove == Color.WHITE ? " w " : " b ");

        StringBuilder castling = new StringBuilder();
        for (CastlingRight right : CastlingRight.values()) {
            if (board.hasCastlingRight(right)) {
                castling.append(right.fenSymbol());
            }
        }
        sb.append(castling.length() == 0 ? "-" : castling);
        sb.append(' ').append(ep == null ? "-" : ep.toString());
        return sb.toString();
    }
}
