package com.lld.games.snakeandladder.game;

import com.lld.games.snakeandladder.model.BoardEntity;
import com.lld.games.snakeandladder.model.Player;

import java.util.Optional;

/**
 * Immutable record of what happened in a single turn. Returned to callers and pushed to listeners,
 * so UI / logging / replay code never has to reach into Game internals.
 *
 * @param player   who moved
 * @param roll     dice value
 * @param from     position before the roll
 * @param landedOn cell reached by the roll alone, after applying the {@link OvershootPolicy}
 * @param to       final position after any snake or ladder
 * @param entity   snake/ladder that was triggered, or null
 * @param overshot true if the roll would have gone past the last cell
 * @param won      true if this move reached the last cell
 */
public record TurnResult(Player player, int roll, int from, int landedOn, int to,
                         BoardEntity entity, boolean overshot, boolean won) {

    public Optional<BoardEntity> triggeredEntity() {
        return Optional.ofNullable(entity);
    }
}
