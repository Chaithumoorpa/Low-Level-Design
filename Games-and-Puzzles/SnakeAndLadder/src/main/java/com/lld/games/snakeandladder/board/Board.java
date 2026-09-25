package com.lld.games.snakeandladder.board;

import com.lld.games.snakeandladder.exception.InvalidBoardException;
import com.lld.games.snakeandladder.model.BoardEntity;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The playing surface: cells 1..size plus snakes and ladders indexed by start cell.
 *
 * <p>Invariants enforced at construction:
 * <ul>
 *   <li>Every entity start/end lies inside [1, size].</li>
 *   <li>No entity starts on cell 1 or on the final cell.</li>
 *   <li>At most one entity starts on any cell.</li>
 *   <li>No entity ends where another starts (no chains, therefore no infinite loops).</li>
 * </ul>
 */
public class Board {

    private final int size;
    private final Map<Integer, BoardEntity> entitiesByStart = new HashMap<>();

    public Board(int size, Collection<? extends BoardEntity> entities) {
        if (size < 2) {
            throw new InvalidBoardException("Board size must be at least 2, got " + size);
        }
        this.size = size;

        for (BoardEntity entity : entities) {
            validateBounds(entity);
            if (entitiesByStart.putIfAbsent(entity.getStart(), entity) != null) {
                throw new InvalidBoardException("Two entities start at cell " + entity.getStart());
            }
        }
        validateNoChains();
    }

    private void validateBounds(BoardEntity e) {
        if (e.getStart() <= 1 || e.getStart() >= size) {
            throw new InvalidBoardException(e + " must start strictly between 1 and " + size);
        }
        if (e.getEnd() < 1 || e.getEnd() > size) {
            throw new InvalidBoardException(e + " must end within [1, " + size + "]");
        }
    }

    private void validateNoChains() {
        for (BoardEntity e : entitiesByStart.values()) {
            if (entitiesByStart.containsKey(e.getEnd())) {
                throw new InvalidBoardException(e + " ends on the start of another entity");
            }
        }
    }

    public int getSize() {
        return size;
    }

    /** Returns the snake/ladder starting at {@code position}, if any. */
    public Optional<BoardEntity> getEntityAt(int position) {
        return Optional.ofNullable(entitiesByStart.get(position));
    }

    /** Where a player who lands on {@code position} finally ends up. O(1). */
    public int getFinalPosition(int position) {
        return getEntityAt(position).map(BoardEntity::getEnd).orElse(position);
    }

    public Collection<BoardEntity> getEntities() {
        return Collections.unmodifiableCollection(entitiesByStart.values());
    }
}
