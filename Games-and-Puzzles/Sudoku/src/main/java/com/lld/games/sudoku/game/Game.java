package com.lld.games.sudoku.game;

import com.lld.games.sudoku.board.Board;
import com.lld.games.sudoku.command.Command;
import com.lld.games.sudoku.command.SetValueCommand;
import com.lld.games.sudoku.command.ToggleNoteCommand;
import com.lld.games.sudoku.exception.InvalidMoveException;
import com.lld.games.sudoku.listener.GameEventListener;
import com.lld.games.sudoku.model.Position;
import com.lld.games.sudoku.solver.BacktrackingSolver;
import com.lld.games.sudoku.solver.SudokuSolver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Facade for one puzzle: place / clear / notes, undo / redo (Command pattern), hints,
 * conflict reporting and solved detection.
 *
 * <p>The solution is computed once, from the given clues only, when the game starts. Hints read
 * from it, and a puzzle without any solution is rejected up front.
 */
public class Game {

    private final Board board;
    private final ValidationMode mode;
    private final int[][] solution;
    private final List<GameEventListener> listeners = new ArrayList<>();
    private final Deque<Command> undoStack = new ArrayDeque<>();
    private final Deque<Command> redoStack = new ArrayDeque<>();

    private GameStatus status = GameStatus.IN_PROGRESS;
    private int movesMade;
    private int hintsUsed;

    public Game(Board puzzle) {
        this(puzzle, ValidationMode.STRICT, new BacktrackingSolver());
    }

    public Game(Board puzzle, ValidationMode mode, SudokuSolver solver) {
        this.board = puzzle;
        this.mode = mode;
        Board givensOnly = givensOf(puzzle);
        this.solution = solver.solve(givensOnly)
                .orElseThrow(() -> new IllegalArgumentException("This puzzle has no solution"));
        if (board.isSolved()) {
            status = GameStatus.SOLVED;
        }
    }

    public void addListener(GameEventListener listener) {
        listeners.add(listener);
    }

    // ------------------------------------------------------------------ player actions

    /** Writes {@code value} (1..N) at (row, col), 0-based. */
    public void place(int row, int col, int value) {
        ensureActive();
        if (value < 1 || value > board.getSize()) {
            throw new InvalidMoveException("Value must be between 1 and " + board.getSize());
        }
        if (board.isGiven(row, col)) {
            throw new InvalidMoveException("Cell " + Position.of(row, col) + " is a given clue and cannot be changed");
        }
        if (mode == ValidationMode.STRICT && board.conflicts(row, col, value)) {
            throw new InvalidMoveException("Can't place " + value + " at " + Position.of(row, col)
                    + ": " + board.conflictReason(row, col, value));
        }
        run(new SetValueCommand(board, Position.of(row, col), value));
    }

    public void clear(int row, int col) {
        ensureActive();
        if (board.isGiven(row, col)) {
            throw new InvalidMoveException("Cell " + Position.of(row, col) + " is a given clue and cannot be changed");
        }
        if (board.isEmpty(row, col)) {
            return;
        }
        run(new SetValueCommand(board, Position.of(row, col), 0));
    }

    /** Adds or removes a pencil mark on an empty cell. */
    public void toggleNote(int row, int col, int digit) {
        ensureActive();
        if (digit < 1 || digit > board.getSize()) {
            throw new InvalidMoveException("Note must be between 1 and " + board.getSize());
        }
        if (!board.isEmpty(row, col)) {
            throw new InvalidMoveException("Notes are only allowed on empty cells");
        }
        run(new ToggleNoteCommand(board, Position.of(row, col), digit));
    }

    /**
     * Fills one cell with its correct value: first a cell holding a wrong digit (so the player is
     * put back on track), otherwise the empty cell with the fewest candidates.
     */
    public Optional<Position> hint() {
        ensureActive();
        Position target = findHintTarget();
        if (target == null) {
            return Optional.empty();
        }
        hintsUsed++;
        run(new SetValueCommand(board, target, solution[target.row()][target.col()]));
        return Optional.of(target);
    }

    public boolean undo() {
        if (undoStack.isEmpty()) {
            return false;
        }
        Command command = undoStack.pop();
        command.undo();
        redoStack.push(command);
        status = board.isSolved() ? GameStatus.SOLVED : GameStatus.IN_PROGRESS;
        notifyCellChange(command, true);
        return true;
    }

    public boolean redo() {
        if (redoStack.isEmpty()) {
            return false;
        }
        Command command = redoStack.pop();
        command.execute();
        undoStack.push(command);
        notifyCellChange(command, false);
        updateStatus();
        return true;
    }

    // ------------------------------------------------------------------ internals

    private void run(Command command) {
        command.execute();
        undoStack.push(command);
        redoStack.clear();                   // a new move invalidates the redo history
        movesMade++;
        notifyCellChange(command, false);
        updateStatus();
    }

    private void updateStatus() {
        if (status == GameStatus.IN_PROGRESS && board.isSolved()) {
            status = GameStatus.SOLVED;
            listeners.forEach(l -> l.onSolved(movesMade, hintsUsed));
        }
    }

    private Position findHintTarget() {
        int n = board.getSize();
        Position best = null;
        int bestCandidates = Integer.MAX_VALUE;
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                int v = board.get(r, c);
                if (v != 0 && v != solution[r][c]) {
                    return Position.of(r, c);                     // fix mistakes first
                }
                if (v == 0) {
                    int candidates = Integer.bitCount(board.candidateMask(r, c));
                    if (candidates < bestCandidates) {
                        bestCandidates = candidates;
                        best = Position.of(r, c);
                    }
                }
            }
        }
        return best;
    }

    private void ensureActive() {
        if (status == GameStatus.SOLVED) {
            throw new InvalidMoveException("The puzzle is already solved");
        }
    }

    private static Board givensOf(Board board) {
        int n = board.getSize();
        int[][] grid = new int[n][n];
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                if (board.isGiven(r, c)) {
                    grid[r][c] = board.get(r, c);
                }
            }
        }
        return Board.fromGrid(grid, board.getBoxRows(), board.getBoxCols(), true);
    }

    /** Listeners hear about value changes from moves, hints, undos and redos in the same way. */
    private void notifyCellChange(Command command, boolean undone) {
        if (command instanceof SetValueCommand set) {
            int from = undone ? set.getNewValue() : set.getOldValue();
            int to = undone ? set.getOldValue() : set.getNewValue();
            listeners.forEach(l -> l.onCellChanged(set.getPosition(), from, to));
        }
    }

    // ------------------------------------------------------------------ queries

    public Board getBoard() {
        return board;
    }

    public GameStatus getStatus() {
        return status;
    }

    public Set<Position> getConflicts() {
        return board.conflictingCells();
    }

    public int getMovesMade() {
        return movesMade;
    }

    public int getHintsUsed() {
        return hintsUsed;
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /** The correct value of a cell (for "reveal" features and tests). */
    public int solutionAt(int row, int col) {
        return solution[row][col];
    }
}
