package com.lld.games.snakeandladder.listener;

import com.lld.games.snakeandladder.game.TurnResult;
import com.lld.games.snakeandladder.model.Player;

/**
 * Observer pattern: lets console output, a GUI, a websocket broadcaster or an audit log react to
 * game events without the Game knowing about any of them.
 */
public interface GameEventListener {

    default void onTurn(TurnResult result) {
    }

    default void onGameOver(Player winner) {
    }
}
