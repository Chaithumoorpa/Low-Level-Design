package com.lld.games.chess.listener;

import com.lld.games.chess.game.EndReason;
import com.lld.games.chess.game.GameStatus;
import com.lld.games.chess.model.Color;
import com.lld.games.chess.model.Move;

/**
 * Observer pattern: clocks, move-list panels, sound effects, a PGN recorder or a network
 * broadcaster can follow the game without the Game knowing about them.
 */
public interface GameEventListener {

    default void onMove(Move move) {
    }

    default void onCheck(Color colorInCheck) {
    }

    default void onUndo(Move undone) {
    }

    default void onGameOver(GameStatus status, EndReason reason) {
    }
}
