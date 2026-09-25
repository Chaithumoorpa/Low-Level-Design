package com.lld.games.chess.board;

import com.lld.games.chess.model.Color;
import com.lld.games.chess.model.Move;
import com.lld.games.chess.model.Position;
import com.lld.games.chess.piece.Piece;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns pseudo-legal moves (what each piece's movement allows) into legal moves
 * (those that do not leave the mover's own king in check).
 *
 * <p>One rule, applied in one place, covers every tricky case: pinned pieces, moving the king
 * into check, blocking or capturing to escape check, and the "discovered check" en passant.
 */
public class MoveGenerator {

    public List<Move> legalMoves(Board board, Color side) {
        List<Move> legal = new ArrayList<>();
        for (Position from : board.positionsOf(side)) {
            legal.addAll(legalMovesFrom(board, from));
        }
        return legal;
    }

    public List<Move> legalMovesFrom(Board board, Position from) {
        Piece piece = board.pieceAt(from);
        if (piece == null) {
            return List.of();
        }
        List<Move> legal = new ArrayList<>();
        for (Move move : piece.pseudoLegalMoves(board, from)) {
            if (keepsKingSafe(board, move)) {
                legal.add(move);
            }
        }
        return legal;
    }

    /** Try the move on a copy and ask: is my king attacked afterwards? */
    public boolean keepsKingSafe(Board board, Move move) {
        Board next = board.copy();
        next.apply(move);
        return !next.isInCheck(move.piece().getColor());
    }

    public boolean hasAnyLegalMove(Board board, Color side) {
        for (Position from : board.positionsOf(side)) {
            if (!legalMovesFrom(board, from).isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
