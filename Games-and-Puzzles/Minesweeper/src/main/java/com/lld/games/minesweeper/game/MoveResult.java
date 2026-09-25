package com.lld.games.minesweeper.game;

import com.lld.games.minesweeper.model.Position;

import java.util.List;

/**
 * Immutable outcome of a reveal or chord. UIs redraw only {@code revealed} instead of the whole board.
 *
 * @param target   the cell the player acted on
 * @param revealed every cell opened by this move (flood fill included); empty if nothing changed
 * @param status   game status after the move
 */
public record MoveResult(Position target, List<Position> revealed, GameStatus status) {

    public MoveResult {
        revealed = List.copyOf(revealed);
    }

    public static MoveResult noChange(Position target, GameStatus status) {
        return new MoveResult(target, List.of(), status);
    }

    public boolean changedBoard() {
        return !revealed.isEmpty();
    }
}
