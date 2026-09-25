package com.lld.games.minesweeper.game;

import com.lld.games.minesweeper.board.Board;
import com.lld.games.minesweeper.board.placement.MinePlacementStrategy;
import com.lld.games.minesweeper.board.placement.RandomMinePlacementStrategy;
import com.lld.games.minesweeper.exception.InvalidMoveException;
import com.lld.games.minesweeper.listener.GameEventListener;
import com.lld.games.minesweeper.model.Cell;
import com.lld.games.minesweeper.model.Position;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Facade for one Minesweeper game. The three player actions are {@link #reveal}, {@link #toggleFlag}
 * and {@link #chord}; everything else (flood fill, win/lose checks, notifications) happens inside.
 *
 * <p>Mines are placed lazily on the first reveal so the first click can be guaranteed safe.
 */
public class Game {

    private final Board board;
    private final int mineCount;
    private final MinePlacementStrategy placementStrategy;
    private final boolean firstClickSafe;
    private final List<GameEventListener> listeners;

    private GameStatus status = GameStatus.NOT_STARTED;
    private int flagsPlaced;
    private Position explodedAt;

    private Game(Builder b) {
        this.board = new Board(b.rows, b.cols);
        this.mineCount = b.mines;
        this.placementStrategy = b.placementStrategy;
        this.firstClickSafe = b.firstClickSafe;
        this.listeners = List.copyOf(b.listeners);
    }

    // ------------------------------------------------------------------ player actions

    public MoveResult reveal(int row, int col) {
        return reveal(new Position(row, col));
    }

    /** Opens a cell. Hitting a mine loses; opening the last safe cell wins. */
    public MoveResult reveal(Position p) {
        Cell cell = playableCell(p);
        if (!cell.isHidden()) {
            return MoveResult.noChange(p, status); // flagged or already open: ignore
        }
        if (status == GameStatus.NOT_STARTED) {
            start(p);
        }
        return afterReveal(p, board.revealFrom(p));
    }

    public boolean toggleFlag(int row, int col) {
        return toggleFlag(new Position(row, col));
    }

    /**
     * Flags a hidden cell or removes a flag.
     *
     * @return true if the cell is flagged after the call
     */
    public boolean toggleFlag(Position p) {
        Cell cell = playableCell(p);
        if (!cell.toggleFlag()) {
            return false; // revealed cells cannot be flagged
        }
        flagsPlaced += cell.isFlagged() ? 1 : -1;
        listeners.forEach(l -> l.onFlagToggled(p, cell.isFlagged()));
        return cell.isFlagged();
    }

    public MoveResult chord(int row, int col) {
        return chord(new Position(row, col));
    }

    /**
     * "Chording": on a revealed number whose neighbouring flags equal that number,
     * reveal all its other hidden neighbours at once. Wrong flags mean you can lose here.
     */
    public MoveResult chord(Position p) {
        Cell cell = playableCell(p);
        if (!cell.isRevealed() || cell.getAdjacentMines() == 0) {
            return MoveResult.noChange(p, status);
        }
        List<Position> neighbours = board.neighbours(p);
        long flags = neighbours.stream().filter(n -> board.getCell(n).isFlagged()).count();
        if (flags != cell.getAdjacentMines()) {
            return MoveResult.noChange(p, status);
        }

        List<Position> opened = new ArrayList<>();
        for (Position n : neighbours) {
            if (board.getCell(n).isHidden()) {
                opened.addAll(board.revealFrom(n));
            }
        }
        return afterReveal(p, opened);
    }

    // ------------------------------------------------------------------ internals

    private Cell playableCell(Position p) {
        if (status.isOver()) {
            throw new InvalidMoveException("Game is over (" + status + ")");
        }
        return board.getCell(p); // throws if out of bounds
    }

    private void start(Position firstClick) {
        Set<Position> safeZone = new HashSet<>();
        if (firstClickSafe) {
            safeZone.add(firstClick);
            safeZone.addAll(board.neighbours(firstClick));
            if (board.getRows() * board.getCols() - safeZone.size() < mineCount) {
                safeZone = Set.of(firstClick); // dense board: protect just the clicked cell
            }
        }
        Set<Position> mines = placementStrategy.placeMines(
                board.getRows(), board.getCols(), mineCount, safeZone);
        if (mines.size() != mineCount) {
            throw new IllegalStateException("Strategy placed " + mines.size()
                    + " mines, expected " + mineCount);
        }
        board.placeMines(mines);
        status = GameStatus.IN_PROGRESS;
    }

    private MoveResult afterReveal(Position target, List<Position> opened) {
        Optional<Position> mine = opened.stream().filter(p -> board.getCell(p).isMine()).findFirst();

        if (mine.isPresent()) {
            status = GameStatus.LOST;
            explodedAt = mine.get();
            board.revealAllMines();
        } else if (board.allSafeCellsRevealed()) {
            status = GameStatus.WON;
        }

        if (!opened.isEmpty()) {
            listeners.forEach(l -> l.onCellsRevealed(opened));
        }
        if (status == GameStatus.LOST) {
            listeners.forEach(l -> l.onGameLost(explodedAt));
        } else if (status == GameStatus.WON) {
            listeners.forEach(GameEventListener::onGameWon);
        }
        return new MoveResult(target, opened, status);
    }

    // ------------------------------------------------------------------ queries

    public GameStatus getStatus() {
        return status;
    }

    public Board getBoard() {
        return board;
    }

    public int getMineCount() {
        return mineCount;
    }

    /** The classic "mines left" counter: total mines minus flags placed (can go negative). */
    public int getRemainingFlags() {
        return mineCount - flagsPlaced;
    }

    public Optional<Position> getExplodedAt() {
        return Optional.ofNullable(explodedAt);
    }

    public static Builder builder() {
        return new Builder();
    }

    // ------------------------------------------------------------------ builder

    public static class Builder {

        private int rows = Difficulty.BEGINNER.getRows();
        private int cols = Difficulty.BEGINNER.getCols();
        private int mines = Difficulty.BEGINNER.getMines();
        private MinePlacementStrategy placementStrategy = new RandomMinePlacementStrategy();
        private boolean firstClickSafe = true;
        private final List<GameEventListener> listeners = new ArrayList<>();

        public Builder difficulty(Difficulty d) {
            return custom(d.getRows(), d.getCols(), d.getMines());
        }

        public Builder custom(int rows, int cols, int mines) {
            this.rows = rows;
            this.cols = cols;
            this.mines = mines;
            return this;
        }

        public Builder placementStrategy(MinePlacementStrategy strategy) {
            this.placementStrategy = strategy;
            return this;
        }

        public Builder firstClickSafe(boolean firstClickSafe) {
            this.firstClickSafe = firstClickSafe;
            return this;
        }

        public Builder addListener(GameEventListener listener) {
            listeners.add(listener);
            return this;
        }

        public Game build() {
            if (rows < 1 || cols < 1) {
                throw new IllegalArgumentException("Board must be at least 1x1");
            }
            if (mines < 1 || mines >= rows * cols) {
                throw new IllegalArgumentException("Mines must be between 1 and " + (rows * cols - 1));
            }
            if (placementStrategy == null) {
                throw new IllegalArgumentException("Placement strategy is required");
            }
            return new Game(this);
        }
    }
}
