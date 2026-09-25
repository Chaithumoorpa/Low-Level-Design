package com.lld.games.chess.board;

import com.lld.games.chess.model.CastlingRight;
import com.lld.games.chess.model.Color;
import com.lld.games.chess.model.Move;
import com.lld.games.chess.model.MoveType;
import com.lld.games.chess.model.PieceType;
import com.lld.games.chess.model.Position;
import com.lld.games.chess.piece.Directions;
import com.lld.games.chess.piece.Piece;
import com.lld.games.chess.piece.PieceFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Piece placement plus the two bits of state that affect which moves exist:
 * castling rights and the en passant target square.
 *
 * <p>The board applies moves mechanically ({@link #apply}); it does NOT check legality.
 * That separation lets MoveGenerator "try" a move on a copy and see if the king is safe.
 */
public class Board {

    private final Piece[][] squares = new Piece[8][8]; // [rank][file]
    private final EnumSet<CastlingRight> castlingRights;
    private Position enPassantTarget;

    public Board() {
        this.castlingRights = EnumSet.noneOf(CastlingRight.class);
    }

    private Board(Board other) {
        for (int r = 0; r < 8; r++) {
            System.arraycopy(other.squares[r], 0, squares[r], 0, 8);
        }
        this.castlingRights = EnumSet.copyOf(other.castlingRights);
        this.enPassantTarget = other.enPassantTarget;
    }

    /** Cheap: 64 references + two small fields. Pieces are shared flyweights. */
    public Board copy() {
        return new Board(this);
    }

    // ------------------------------------------------------------------ squares

    public Piece pieceAt(Position p) {
        return squares[p.rank()][p.file()];
    }

    public void place(Position p, Piece piece) {
        squares[p.rank()][p.file()] = piece;
    }

    public Piece remove(Position p) {
        Piece piece = pieceAt(p);
        squares[p.rank()][p.file()] = null;
        return piece;
    }

    public List<Position> positionsOf(Color color) {
        List<Position> result = new ArrayList<>(16);
        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 8; f++) {
                Piece piece = squares[r][f];
                if (piece != null && piece.getColor() == color) {
                    result.add(new Position(f, r));
                }
            }
        }
        return result;
    }

    public Position findKing(Color color) {
        for (Position p : positionsOf(color)) {
            if (pieceAt(p).getType() == PieceType.KING) {
                return p;
            }
        }
        throw new IllegalStateException(color.displayName() + " has no king on the board");
    }

    // ------------------------------------------------------------------ castling / en passant

    public boolean hasCastlingRight(CastlingRight right) {
        return castlingRights.contains(right);
    }

    public Set<CastlingRight> getCastlingRights() {
        return EnumSet.copyOf(castlingRights);
    }

    public void setCastlingRights(Set<CastlingRight> rights) {
        castlingRights.clear();
        castlingRights.addAll(rights);
    }

    public Position getEnPassantTarget() {
        return enPassantTarget;
    }

    public void setEnPassantTarget(Position enPassantTarget) {
        this.enPassantTarget = enPassantTarget;
    }

    // ------------------------------------------------------------------ applying moves

    /** Executes a move, including all side effects of castling, en passant and promotion. */
    public void apply(Move move) {
        Piece piece = remove(move.from());
        Color color = piece.getColor();
        int rank = move.from().rank();

        switch (move.type()) {
            case EN_PASSANT -> remove(new Position(move.to().file(), rank));
            case CASTLE_KINGSIDE -> place(new Position(5, rank), remove(new Position(7, rank)));
            case CASTLE_QUEENSIDE -> place(new Position(3, rank), remove(new Position(0, rank)));
            default -> { }
        }

        Piece landing = move.type() == MoveType.PROMOTION ? PieceFactory.get(move.promotion(), color) : piece;
        place(move.to(), landing);

        updateCastlingRights(move, piece);
        enPassantTarget = move.type() == MoveType.DOUBLE_PAWN_PUSH
                ? new Position(move.from().file(), (move.from().rank() + move.to().rank()) / 2)
                : null;
    }

    private void updateCastlingRights(Move move, Piece piece) {
        if (piece.getType() == PieceType.KING) {
            castlingRights.remove(CastlingRight.of(piece.getColor(), true));
            castlingRights.remove(CastlingRight.of(piece.getColor(), false));
        }
        // A rook leaving its corner, or anything capturing on that corner, ends that right.
        castlingRights.removeIf(r -> r.rookSquare().equals(move.from()) || r.rookSquare().equals(move.to()));
    }

    // ------------------------------------------------------------------ attack detection

    /**
     * Is {@code square} attacked by any piece of color {@code by}?
     *
     * <p>Looks outward FROM the square ("reverse lookup") instead of generating every enemy move:
     * is there an enemy knight a knight's jump away, an enemy rook/queen along a line, etc.
     * This is called a lot (every legality check, every castling check), so it has to be fast.
     */
    public boolean isAttacked(Position square, Color by) {
        int f = square.file();
        int r = square.rank();

        int pawnRank = r - by.pawnDirection(); // an enemy pawn attacks from one rank "behind" the square
        for (int df : new int[]{-1, 1}) {
            if (isPieceAt(f + df, pawnRank, by, PieceType.PAWN)) {
                return true;
            }
        }
        for (int[] d : Directions.KNIGHT) {
            if (isPieceAt(f + d[0], r + d[1], by, PieceType.KNIGHT)) {
                return true;
            }
        }
        for (int[] d : Directions.ALL) {
            if (isPieceAt(f + d[0], r + d[1], by, PieceType.KING)) {
                return true;
            }
        }
        return rayHits(f, r, Directions.ORTHOGONAL, by, PieceType.ROOK)
                || rayHits(f, r, Directions.DIAGONAL, by, PieceType.BISHOP);
    }

    public boolean isInCheck(Color color) {
        return isAttacked(findKing(color), color.opposite());
    }

    private boolean isPieceAt(int f, int r, Color color, PieceType type) {
        if (!Position.inBounds(f, r)) {
            return false;
        }
        Piece p = squares[r][f];
        return p != null && p.getColor() == color && p.getType() == type;
    }

    /** Walks each ray to the first piece; a hit is an enemy {@code slider} or an enemy queen. */
    private boolean rayHits(int f, int r, int[][] directions, Color by, PieceType slider) {
        for (int[] d : directions) {
            int cf = f + d[0];
            int cr = r + d[1];
            while (Position.inBounds(cf, cr)) {
                Piece p = squares[cr][cf];
                if (p != null) {
                    if (p.getColor() == by && (p.getType() == slider || p.getType() == PieceType.QUEEN)) {
                        return true;
                    }
                    break;
                }
                cf += d[0];
                cr += d[1];
            }
        }
        return false;
    }
}
