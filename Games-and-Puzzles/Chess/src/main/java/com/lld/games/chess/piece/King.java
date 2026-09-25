package com.lld.games.chess.piece;

import com.lld.games.chess.board.Board;
import com.lld.games.chess.model.CastlingRight;
import com.lld.games.chess.model.Color;
import com.lld.games.chess.model.Move;
import com.lld.games.chess.model.MoveType;
import com.lld.games.chess.model.PieceType;
import com.lld.games.chess.model.Position;

import java.util.List;

/** Moves one square in any direction, plus castling. */
public class King extends Piece {

    public King(Color color) {
        super(color);
    }

    @Override
    public PieceType getType() {
        return PieceType.KING;
    }

    @Override
    public List<Position> attackedSquares(Board board, Position from) {
        return step(from, Directions.ALL);
    }

    @Override
    public List<Move> pseudoLegalMoves(Board board, Position from) {
        List<Move> moves = super.pseudoLegalMoves(board, from);
        addCastling(board, from, moves, true);
        addCastling(board, from, moves, false);
        return moves;
    }

    /**
     * Castling needs: the right still held, the king and rook on their original squares,
     * the squares between them empty, and the king not in check, not passing through an
     * attacked square, and not landing on one. (Landing is re-checked by MoveGenerator anyway.)
     */
    private void addCastling(Board board, Position from, List<Move> moves, boolean kingside) {
        Color color = getColor();
        int rank = color.homeRank();
        CastlingRight right = CastlingRight.of(color, kingside);

        if (!board.hasCastlingRight(right) || !from.equals(new Position(4, rank))) {
            return;
        }
        Piece rook = board.pieceAt(right.rookSquare());
        if (rook == null || rook.getType() != PieceType.ROOK || rook.getColor() != color) {
            return;
        }

        int[] mustBeEmpty = kingside ? new int[]{5, 6} : new int[]{1, 2, 3};
        for (int file : mustBeEmpty) {
            if (board.pieceAt(new Position(file, rank)) != null) {
                return;
            }
        }

        Color enemy = color.opposite();
        int[] kingPath = kingside ? new int[]{4, 5, 6} : new int[]{4, 3, 2};
        for (int file : kingPath) {
            if (board.isAttacked(new Position(file, rank), enemy)) {
                return;
            }
        }

        Position to = new Position(kingside ? 6 : 2, rank);
        MoveType type = kingside ? MoveType.CASTLE_KINGSIDE : MoveType.CASTLE_QUEENSIDE;
        moves.add(new Move(from, to, this, null, type, null));
    }
}
