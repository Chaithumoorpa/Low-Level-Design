package com.lld.games.tictactoe.listener;

import com.lld.games.tictactoe.game.GameStatus;
import com.lld.games.tictactoe.model.Move;
import com.lld.games.tictactoe.model.Player;

/** Observer pattern: UIs, scoreboards or loggers react to the game without the Game knowing them. */
public interface GameEventListener {

    default void onMove(Move move) {
    }

    /** @param winner the winning player, or null for a draw */
    default void onGameOver(GameStatus status, Player winner) {
    }
}
