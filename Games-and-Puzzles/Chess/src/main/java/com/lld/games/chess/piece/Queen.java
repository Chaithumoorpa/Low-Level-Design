package com.lld.games.chess.piece;

import com.lld.games.chess.board.Board;
import com.lld.games.chess.model.Color;
import com.lld.games.chess.model.PieceType;
import com.lld.games.chess.model.Position;

import java.util.List;

/** Rook + bishop: slides any distance in all eight directions. */
public class Queen extends Piece {

    public Queen(Color color) {
        super(color);
    }

    @Override
    public PieceType getType() {
        return PieceType.QUEEN;
    }

    @Override
    public List<Position> attackedSquares(Board board, Position from) {
        return slide(board, from, Directions.ALL);
    }
}
