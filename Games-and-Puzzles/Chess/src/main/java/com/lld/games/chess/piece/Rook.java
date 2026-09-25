package com.lld.games.chess.piece;

import com.lld.games.chess.board.Board;
import com.lld.games.chess.model.Color;
import com.lld.games.chess.model.PieceType;
import com.lld.games.chess.model.Position;

import java.util.List;

/** Slides any distance along ranks and files. */
public class Rook extends Piece {

    public Rook(Color color) {
        super(color);
    }

    @Override
    public PieceType getType() {
        return PieceType.ROOK;
    }

    @Override
    public List<Position> attackedSquares(Board board, Position from) {
        return slide(board, from, Directions.ORTHOGONAL);
    }
}
