package com.game.tic_tac_toe.core;

import com.game.tic_tac_toe.model.Player;
import com.game.tic_tac_toe.enums.GameStatus;
import com.game.tic_tac_toe.enums.Symbol;
import com.game.tic_tac_toe.exceptions.InvalidMoveException;

public class Game {
    private final Board board;
    private final Player[] players;
    private int currentPlayerIndex;
    private GameStatus status;

    public Game(Player player1, Player player2, int boardSize) {
        this.board = new Board(boardSize);
        this.players = new Player[]{player1, player2};
        this.currentPlayerIndex = 0;
        this.status = GameStatus.IN_PROGRESS;
    }

    public synchronized void makeMove(int row, int col) {
        // Check if game is already over
        if (status != GameStatus.IN_PROGRESS) {
            throw new InvalidMoveException("Game is already over!");
        }

        // Validate the move
        if (!board.isCellEmpty(row, col)) {
            throw new InvalidMoveException(
                "Cell (" + row + ", " + col + ") is already occupied"
            );
        }

        // Place the symbol
        Player currentPlayer = players[currentPlayerIndex];
        board.placeSymbol(row, col, currentPlayer.getSymbol());

        // Check for win
        if (checkWin(row, col, currentPlayer.getSymbol())) {
            status = (currentPlayer.getSymbol() == Symbol.X)
                ? GameStatus.WINNER_X
                : GameStatus.WINNER_O;
            return;
        }

        // Check for draw
        if (board.isFull()) {
            status = GameStatus.DRAW;
            return;
        }

        // Switch to next player
        currentPlayerIndex = (currentPlayerIndex + 1) % 2;
    }

    public int getCurrentPlayerIndex() {
		return currentPlayerIndex;
	}

	public void setCurrentPlayerIndex(int currentPlayerIndex) {
		this.currentPlayerIndex = currentPlayerIndex;
	}

	public Player[] getPlayers() {
		return players;
	}

	public void setStatus(GameStatus status) {
		this.status = status;
	}

	private boolean checkWin(int row, int col, Symbol symbol) {
        int size = board.getSize();

        // Check the row of the last move
        boolean win = true;
        for (int c = 0; c < size; c++) {
            if (board.getCell(row, c).getSymbol() != symbol) { win = false; break; }
        }
        if (win) return true;

        // Check the column of the last move
        win = true;
        for (int r = 0; r < size; r++) {
            if (board.getCell(r, col).getSymbol() != symbol) { win = false; break; }
        }
        if (win) return true;

        // Check the main diagonal (only if the move is on it)
        if (row == col) {
            win = true;
            for (int i = 0; i < size; i++) {
                if (board.getCell(i, i).getSymbol() != symbol) { win = false; break; }
            }
            if (win) return true;
        }

        // Check the anti-diagonal (only if the move is on it)
        if (row + col == size - 1) {
            win = true;
            for (int i = 0; i < size; i++) {
                if (board.getCell(i, size - 1 - i).getSymbol() != symbol) { win = false; break; }
            }
            if (win) return true;
        }

        return false;
    }

    public Board getBoard() { return board; }
    public Player getCurrentPlayer() { return players[currentPlayerIndex]; }
    public GameStatus getStatus() { return status; }

    public Player getWinner() {
        if (status == GameStatus.WINNER_X) {
            return players[0].getSymbol() == Symbol.X ? players[0] : players[1];
        } else if (status == GameStatus.WINNER_O) {
            return players[0].getSymbol() == Symbol.O ? players[0] : players[1];
        }
        return null;
    }

    public void printBoard() {
        board.printBoard();
    }
}