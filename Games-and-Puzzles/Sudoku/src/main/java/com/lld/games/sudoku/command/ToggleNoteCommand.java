package com.lld.games.sudoku.command;

import com.lld.games.sudoku.board.Board;
import com.lld.games.sudoku.model.Position;

/** Adds or removes one pencil mark. Toggling is its own inverse, so undo simply toggles again. */
public class ToggleNoteCommand implements Command {

    private final Board board;
    private final Position position;
    private final int digit;

    public ToggleNoteCommand(Board board, Position position, int digit) {
        this.board = board;
        this.position = position;
        this.digit = digit;
    }

    @Override
    public void execute() {
        toggle();
    }

    @Override
    public void undo() {
        toggle();
    }

    private void toggle() {
        int mask = board.getNotesMask(position.row(), position.col());
        board.setNotesMask(position.row(), position.col(), mask ^ (1 << digit));
    }

    @Override
    public String describe() {
        return "note " + digit + " at " + position;
    }
}
