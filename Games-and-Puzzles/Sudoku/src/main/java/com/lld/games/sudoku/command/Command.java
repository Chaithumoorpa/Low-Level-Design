package com.lld.games.sudoku.command;

/**
 * Command pattern: every change to the board is an object that can apply itself and reverse
 * itself. The game keeps two stacks of commands, which is all undo/redo needs.
 */
public interface Command {

    void execute();

    void undo();

    /** Short description for logs and the console, e.g. "r5c3 = 7". */
    String describe();
}
