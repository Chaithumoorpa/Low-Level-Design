package com.lld.games.sudoku.command;

import com.lld.games.sudoku.board.Board;
import com.lld.games.sudoku.model.Position;

/**
 * Writes a value into a cell (0 = clear). Placing a digit also wipes that cell's pencil marks,
 * so the command remembers both the old value and the old notes to restore them on undo.
 */
public class SetValueCommand implements Command {

    private final Board board;
    private final Position position;
    private final int newValue;
    private int oldValue;
    private int oldNotes;

    public SetValueCommand(Board board, Position position, int newValue) {
        this.board = board;
        this.position = position;
        this.newValue = newValue;
    }

    @Override
    public void execute() {
        int r = position.row();
        int c = position.col();
        oldValue = board.get(r, c);
        oldNotes = board.getNotesMask(r, c);
        board.set(r, c, newValue);
        if (newValue != 0) {
            board.setNotesMask(r, c, 0);
        }
    }

    @Override
    public void undo() {
        board.set(position.row(), position.col(), oldValue);
        board.setNotesMask(position.row(), position.col(), oldNotes);
    }

    public Position getPosition() {
        return position;
    }

    public int getNewValue() {
        return newValue;
    }

    public int getOldValue() {
        return oldValue;
    }

    @Override
    public String describe() {
        return newValue == 0 ? "clear " + position : position + " = " + newValue;
    }
}
