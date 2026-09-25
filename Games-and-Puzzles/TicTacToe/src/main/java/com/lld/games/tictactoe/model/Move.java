package com.lld.games.tictactoe.model;

/** One placed mark. Immutable, so it can be stored in history and handed to listeners safely. */
public record Move(Player player, int row, int col) {

    public Symbol symbol() {
        return player.symbol();
    }
}
