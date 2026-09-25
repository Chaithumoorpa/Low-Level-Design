package com.lld.games.chess.game;

import com.lld.games.chess.board.Board;
import com.lld.games.chess.model.Color;
import com.lld.games.chess.model.PieceType;
import com.lld.games.chess.model.Position;
import com.lld.games.chess.piece.Piece;

import java.util.ArrayList;
import java.util.List;

/**
 * Dead positions where no sequence of legal moves can ever produce checkmate:
 * K vs K, K+B vs K, K+N vs K, and kings with only bishops that all stand on one square color.
 */
final class InsufficientMaterial {

    private InsufficientMaterial() {
    }

    static boolean isDraw(Board board) {
        List<Position> extras = new ArrayList<>();
        for (Color color : Color.values()) {
            for (Position p : board.positionsOf(color)) {
                if (board.pieceAt(p).getType() != PieceType.KING) {
                    extras.add(p);
                }
            }
        }

        if (extras.isEmpty()) {
            return true;                                       // K vs K
        }
        if (extras.size() == 1) {
            PieceType type = board.pieceAt(extras.get(0)).getType();
            return type == PieceType.BISHOP || type == PieceType.KNIGHT;  // K+minor vs K
        }

        // Any number of bishops, all on the same square color, can never mate.
        boolean firstLight = extras.get(0).isLightSquare();
        for (Position p : extras) {
            Piece piece = board.pieceAt(p);
            if (piece.getType() != PieceType.BISHOP || p.isLightSquare() != firstLight) {
                return false;
            }
        }
        return true;
    }
}
