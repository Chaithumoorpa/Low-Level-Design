package com.lld.games.chess.piece;

import com.lld.games.chess.board.Board;
import com.lld.games.chess.model.Color;
import com.lld.games.chess.model.PieceType;
import com.lld.games.chess.model.Position;

import java.util.List;

/** Slides any distance diagonally, so it stays on one square color all game. */
public class Bishop extends Piece {

    public Bishop(Color color) {
        super(color);
    }

    @Override
    public PieceType getType() {
        return PieceType.BISHOP;
    }

    @Override
    public List<Position> attackedSquares(Board board, Position from) {
        return slide(board, from, Directions.DIAGONAL);
    }
}
