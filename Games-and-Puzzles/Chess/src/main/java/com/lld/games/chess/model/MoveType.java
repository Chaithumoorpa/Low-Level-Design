package com.lld.games.chess.model;

/** Moves that need extra work when applied to the board, beyond "lift piece, put it down". */
public enum MoveType {
    NORMAL,
    DOUBLE_PAWN_PUSH,   // creates an en passant target
    EN_PASSANT,         // the captured pawn is NOT on the destination square
    CASTLE_KINGSIDE,    // the rook moves too
    CASTLE_QUEENSIDE,
    PROMOTION           // the pawn is replaced by another piece
}
