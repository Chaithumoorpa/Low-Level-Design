package com.lld.games.snakeandladder.game;

import com.lld.games.snakeandladder.board.Board;
import com.lld.games.snakeandladder.board.placement.PlacementStrategy;
import com.lld.games.snakeandladder.dice.Dice;
import com.lld.games.snakeandladder.dice.StandardDice;
import com.lld.games.snakeandladder.listener.GameEventListener;
import com.lld.games.snakeandladder.model.BoardEntity;
import com.lld.games.snakeandladder.model.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Facade over the whole game: callers only need {@link #playTurn()} or {@link #play()}.
 * Built with {@link Builder} so the many optional knobs (dice, board, listeners) stay readable.
 *
 * <p>Turn order is a FIFO queue: poll the current player, move, and offer them back.
 */
public class Game {

    private final Board board;
    private final Dice dice;
    private final OvershootPolicy overshootPolicy;
    private final Deque<Player> turnQueue;
    private final List<Player> players;
    private final List<GameEventListener> listeners;

    private GameStatus status = GameStatus.NOT_STARTED;
    private Player winner;

    private Game(Builder builder) {
        this.board = builder.board;
        this.dice = builder.dice;
        this.overshootPolicy = builder.overshootPolicy;
        this.players = List.copyOf(builder.players);
        this.turnQueue = new ArrayDeque<>(builder.players);
        this.listeners = List.copyOf(builder.listeners);
    }

    /** Plays exactly one turn for the player whose turn it is. */
    public TurnResult playTurn() {
        if (status == GameStatus.FINISHED) {
            throw new IllegalStateException("Game is already over. Winner: " + winner.getName());
        }
        status = GameStatus.IN_PROGRESS;

        Player player = turnQueue.pollFirst();
        int roll = dice.roll();
        TurnResult result = move(player, roll);

        if (result.won()) {
            winner = player;
            status = GameStatus.FINISHED;
        } else {
            turnQueue.offerLast(player);
        }

        listeners.forEach(l -> l.onTurn(result));
        if (result.won()) {
            listeners.forEach(l -> l.onGameOver(player));
        }
        return result;
    }

    /** Plays turns until someone wins and returns the winner. */
    public Player play() {
        while (status != GameStatus.FINISHED) {
            playTurn();
        }
        return winner;
    }

    private TurnResult move(Player player, int roll) {
        int from = player.getPosition();
        int target = from + roll;
        boolean overshot = target > board.getSize();

        if (overshot) {
            // Exact-landing rule: the policy decides where an overshooting roll ends up.
            target = overshootPolicy.resolve(from, target, board.getSize());
        }

        BoardEntity entity = board.getEntityAt(target).orElse(null);
        int finalPosition = board.getFinalPosition(target);
        player.setPosition(finalPosition);

        boolean won = finalPosition == board.getSize();
        return new TurnResult(player, roll, from, target, finalPosition, entity, overshot, won);
    }

    public GameStatus getStatus() {
        return status;
    }

    public Optional<Player> getWinner() {
        return Optional.ofNullable(winner);
    }

    /** The player who will move on the next call to {@link #playTurn()}. */
    public Player getCurrentPlayer() {
        return turnQueue.peekFirst();
    }

    public List<Player> getPlayers() {
        return players;
    }

    public Board getBoard() {
        return board;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder pattern: validates the configuration once, then produces an immutable-setup Game. */
    public static class Builder {

        private static final int DEFAULT_BOARD_SIZE = 100;

        private Board board;
        private int boardSize = DEFAULT_BOARD_SIZE;
        private PlacementStrategy placementStrategy;
        private Dice dice = new StandardDice();
        private OvershootPolicy overshootPolicy = OvershootPolicy.STAY;
        private final List<Player> players = new ArrayList<>();
        private final List<GameEventListener> listeners = new ArrayList<>();

        /** Use a pre-built board. Takes precedence over boardSize + placementStrategy. */
        public Builder board(Board board) {
            this.board = board;
            return this;
        }

        public Builder boardSize(int boardSize) {
            this.boardSize = boardSize;
            return this;
        }

        public Builder placementStrategy(PlacementStrategy placementStrategy) {
            this.placementStrategy = placementStrategy;
            return this;
        }

        public Builder dice(Dice dice) {
            this.dice = dice;
            return this;
        }

        public Builder overshootPolicy(OvershootPolicy overshootPolicy) {
            this.overshootPolicy = overshootPolicy;
            return this;
        }

        public Builder addPlayer(String name) {
            players.add(new Player(name));
            return this;
        }

        public Builder addListener(GameEventListener listener) {
            listeners.add(listener);
            return this;
        }

        public Game build() {
            if (players.size() < 2) {
                throw new IllegalStateException("At least 2 players are required");
            }
            Set<Player> unique = new HashSet<>(players);
            if (unique.size() != players.size()) {
                throw new IllegalStateException("Player names must be unique");
            }
            if (dice == null || overshootPolicy == null) {
                throw new IllegalStateException("Dice and overshoot policy must be provided");
            }
            if (board == null) {
                List<BoardEntity> entities = placementStrategy == null
                        ? List.of()
                        : placementStrategy.place(boardSize);
                board = new Board(boardSize, entities);
            }
            return new Game(this);
        }
    }
}
