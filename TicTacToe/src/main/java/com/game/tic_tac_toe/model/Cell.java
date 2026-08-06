package com.game.tic_tac_toe.model;

import com.game.tic_tac_toe.enums.Symbol;

public class Cell {
    private Symbol symbol;

    public Cell() {
        this.symbol = Symbol.EMPTY;
    }

    public Symbol getSymbol() {
        return symbol;
    }

    public void setSymbol(Symbol symbol) {
        this.symbol = symbol;
    }

    public boolean isEmpty() {
        return symbol == Symbol.EMPTY;
    }
}
