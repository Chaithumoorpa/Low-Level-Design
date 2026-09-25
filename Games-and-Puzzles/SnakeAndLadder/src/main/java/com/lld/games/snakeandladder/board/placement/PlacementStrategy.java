package com.lld.games.snakeandladder.board.placement;

import com.lld.games.snakeandladder.model.BoardEntity;

import java.util.List;

/**
 * Strategy pattern: decides WHERE snakes and ladders go on a board of a given size.
 * Swap implementations (manual, random, difficulty-based, loaded from file...) without
 * changing {@code Board} or {@code Game}.
 */
public interface PlacementStrategy {

    List<BoardEntity> place(int boardSize);
}
