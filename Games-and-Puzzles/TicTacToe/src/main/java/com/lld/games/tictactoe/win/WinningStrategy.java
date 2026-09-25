package com.lld.games.tictactoe.win;

import com.lld.games.tictactoe.board.Board;
import com.lld.games.tictactoe.model.Move;

/**
 * Strategy pattern: decides whether the move just played completed a line.
 * Called once after every move, with the move already placed on the board.
 *
 * <p>Implementations may keep state between calls, so use one instance per game.
 */
public interface WinningStrategy {

    boolean isWinningMove(Board board, Move lastMove);
}
