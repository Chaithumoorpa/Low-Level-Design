package com.lld.games.chess.piece;

import com.lld.games.chess.board.Board;
import com.lld.games.chess.model.Color;
import com.lld.games.chess.model.Move;
import com.lld.games.chess.model.MoveType;
import com.lld.games.chess.model.PieceType;
import com.lld.games.chess.model.Position;

import java.util.ArrayList;
import java.util.List;

/**
 * The most rule-heavy piece: moves straight but captures diagonally, may move two squares from
 * its start rank, captures en passant, and promotes on the last rank.
 */
public class Pawn extends Piece {

    private static final PieceType[] PROMOTION_CHOICES = {
            PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT};

    public Pawn(Color color) {
        super(color);
    }

    @Override
    public PieceType getType() {
        return PieceType.PAWN;
    }

    /** A pawn attacks only the two forward diagonals, even though it cannot move there when empty. */
    @Override
    public List<Position> attackedSquares(Board board, Position from) {
        List<Position> result = new ArrayList<>(2);
        int r = from.rank() + getColor().pawnDirection();
        for (int df : new int[]{-1, 1}) {
            int f = from.file() + df;
            if (Position.inBounds(f, r)) {
                result.add(new Position(f, r));
            }
        }
        return result;
    }

    @Override
    public List<Move> pseudoLegalMoves(Board board, Position from) {
        List<Move> moves = new ArrayList<>();
        Color color = getColor();
        int dir = color.pawnDirection();
        int nextRank = from.rank() + dir;
        if (!Position.inBounds(from.file(), nextRank)) {
            return moves; // cannot happen in a legal game: the pawn would have promoted
        }

        // 1. One square forward, then two from the start rank
        Position one = new Position(from.file(), nextRank);
        if (board.pieceAt(one) == null) {
            addWithPromotions(moves, from, one, null);
            if (from.rank() == color.pawnStartRank()) {
                Position two = new Position(from.file(), nextRank + dir);
                if (board.pieceAt(two) == null) {
                    moves.add(new Move(from, two, this, null, MoveType.DOUBLE_PAWN_PUSH, null));
                }
            }
        }

        // 2. Diagonal captures, including en passant
        for (Position target : attackedSquares(board, from)) {
            Piece victim = board.pieceAt(target);
            if (victim != null && victim.getColor() != color) {
                addWithPromotions(moves, from, target, victim);
            } else if (victim == null && target.equals(board.getEnPassantTarget())) {
                Piece passed = board.pieceAt(new Position(target.file(), from.rank()));
                moves.add(new Move(from, target, this, passed, MoveType.EN_PASSANT, null));
            }
        }
        return moves;
    }

    /** Reaching the last rank yields four separate moves: =Q, =R, =B, =N. */
    private void addWithPromotions(List<Move> moves, Position from, Position to, Piece captured) {
        if (to.rank() == getColor().promotionRank()) {
            for (PieceType type : PROMOTION_CHOICES) {
                moves.add(new Move(from, to, this, captured, MoveType.PROMOTION, type));
            }
        } else {
            moves.add(Move.of(from, to, this, captured));
        }
    }
}
