package com.lld.games.chess.piece;

import com.lld.games.chess.board.Board;
import com.lld.games.chess.model.Color;
import com.lld.games.chess.model.PieceType;
import com.lld.games.chess.model.Position;

import java.util.List;

/** Jumps in an L shape; the only piece that can pass over others. */
public class Knight extends Piece {

    public Knight(Color color) {
        super(color);
    }

    @Override
    public PieceType getType() {
        return PieceType.KNIGHT;
    }

    @Override
    public List<Position> attackedSquares(Board board, Position from) {
        return step(from, Directions.KNIGHT);
    }
}
