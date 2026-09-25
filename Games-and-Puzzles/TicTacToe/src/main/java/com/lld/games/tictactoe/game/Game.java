package com.lld.games.tictactoe.game;

import com.lld.games.tictactoe.board.Board;
import com.lld.games.tictactoe.exception.InvalidMoveException;
import com.lld.games.tictactoe.listener.GameEventListener;
import com.lld.games.tictactoe.model.Move;
import com.lld.games.tictactoe.model.Player;
import com.lld.games.tictactoe.model.Symbol;
import com.lld.games.tictactoe.win.CounterWinningStrategy;
import com.lld.games.tictactoe.win.WinningStrategy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Facade for one game: validates and applies moves, rotates turns, detects win/draw,
 * keeps history and notifies listeners. Created through {@link Builder}.
 */
public class Game {

    private final Board board;
    private final List<Player> players;
    private final WinningStrategy winningStrategy;
    private final List<GameEventListener> listeners;
    private final List<Move> history = new ArrayList<>();

    private int currentPlayerIndex;
    private GameStatus status = GameStatus.IN_PROGRESS;
    private Player winner;

    private Game(Builder b) {
        this.board = new Board(b.boardSize);
        this.players = List.copyOf(b.players);
        this.winningStrategy = b.winningStrategy;
        this.listeners = List.copyOf(b.listeners);
    }

    /** Places the current player's symbol at (row, col). */
    public Move makeMove(int row, int col) {
        if (status.isOver()) {
            throw new InvalidMoveException("Game is already over (" + status + ")");
        }
        Player player = getCurrentPlayer();
        board.place(row, col, player.symbol());   // validates bounds and occupancy

        Move move = new Move(player, row, col);
        history.add(move);

        if (winningStrategy.isWinningMove(board, move)) {
            status = GameStatus.WON;
            winner = player;
        } else if (board.isFull()) {
            status = GameStatus.DRAW;
        } else {
            currentPlayerIndex = (currentPlayerIndex + 1) % players.size();
        }

        listeners.forEach(l -> l.onMove(move));
        if (status.isOver()) {
            listeners.forEach(l -> l.onGameOver(status, winner));
        }
        return move;
    }

    public Player getCurrentPlayer() {
        return players.get(currentPlayerIndex);
    }

    public GameStatus getStatus() {
        return status;
    }

    public Optional<Player> getWinner() {
        return Optional.ofNullable(winner);
    }

    public Board getBoard() {
        return board;
    }

    public List<Player> getPlayers() {
        return players;
    }

    public List<Move> getHistory() {
        return Collections.unmodifiableList(history);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private int boardSize = 3;
        private final List<Player> players = new ArrayList<>();
        private WinningStrategy winningStrategy;
        private final List<GameEventListener> listeners = new ArrayList<>();

        public Builder boardSize(int boardSize) {
            this.boardSize = boardSize;
            return this;
        }

        /** Players move in the order they are added; the first player is X, the second O. */
        public Builder addPlayer(String name) {
            Symbol[] symbols = Symbol.values();
            if (players.size() >= symbols.length) {
                throw new IllegalStateException("Only " + symbols.length + " players are supported");
            }
            players.add(new Player(name, symbols[players.size()]));
            return this;
        }

        /** Optional; defaults to the O(1) counter strategy. Must be a fresh instance per game. */
        public Builder winningStrategy(WinningStrategy winningStrategy) {
            this.winningStrategy = winningStrategy;
            return this;
        }

        public Builder addListener(GameEventListener listener) {
            listeners.add(listener);
            return this;
        }

        public Game build() {
            if (players.size() != Symbol.values().length) {
                throw new IllegalStateException("Exactly " + Symbol.values().length + " players are required");
            }
            if (winningStrategy == null) {
                winningStrategy = new CounterWinningStrategy();
            }
            return new Game(this);
        }
    }
}
