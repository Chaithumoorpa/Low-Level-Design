package com.lld.games.snakeandladder.game;

/**
 * What happens when a roll would carry a player past the last cell.
 *
 * <p>Pick BOUNCE_BACK when the minimum roll is greater than 1 (e.g. two dice): with STAY a player
 * sitting on {@code size - 1} could never land exactly and the game would never end.
 */
public enum OvershootPolicy {

    /** Player does not move this turn (classic rule). */
    STAY {
        @Override
        public int resolve(int from, int target, int boardSize) {
            return from;
        }
    },

    /** Player walks to the last cell and back by the excess, e.g. 98 + 5 on a 100 board -> 97. */
    BOUNCE_BACK {
        @Override
        public int resolve(int from, int target, int boardSize) {
            return Math.max(1, boardSize - (target - boardSize));
        }
    };

    /** Cell the player lands on (before snakes/ladders) when {@code target > boardSize}. */
    public abstract int resolve(int from, int target, int boardSize);
}
