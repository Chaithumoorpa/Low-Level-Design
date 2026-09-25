package com.lld.games.snakeandladder.board.placement;

import com.lld.games.snakeandladder.model.BoardEntity;

import java.util.List;

/** Uses an explicit, caller-supplied list of snakes and ladders (e.g. the classic board). */
public class ManualPlacementStrategy implements PlacementStrategy {

    private final List<BoardEntity> entities;

    public ManualPlacementStrategy(List<? extends BoardEntity> entities) {
        this.entities = List.copyOf(entities);
    }

    @Override
    public List<BoardEntity> place(int boardSize) {
        return entities; // Board validates bounds against boardSize
    }
}
