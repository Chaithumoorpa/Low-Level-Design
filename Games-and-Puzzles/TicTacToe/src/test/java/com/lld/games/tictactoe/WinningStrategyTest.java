package com.lld.games.tictactoe;

import com.lld.games.tictactoe.board.Board;
import com.lld.games.tictactoe.model.Move;
import com.lld.games.tictactoe.model.Player;
import com.lld.games.tictactoe.model.Symbol;
import com.lld.games.tictactoe.win.CounterWinningStrategy;
import com.lld.games.tictactoe.win.ScanWinningStrategy;
import com.lld.games.tictactoe.win.WinningStrategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The O(1) counter strategy must agree with the simple scanning strategy on every move of
 * thousands of random games on every board size. Cross-checking a clever implementation
 * against an obviously-correct one is a cheap, powerful testing technique.
 */
class WinningStrategyTest {

    private static final Player X = new Player("X", Symbol.X);
    private static final Player O = new Player("O", Symbol.O);

    @Test
    void counterAndScanAgreeOnRandomGames() {
        Random random = new Random(2024);
        int movesChecked = 0;

        for (int size = 3; size <= 10; size++) {
            for (int game = 0; game < 500; game++) {
                Board board = new Board(size);
                WinningStrategy counter = new CounterWinningStrategy();
                WinningStrategy scan = new ScanWinningStrategy();

                List<int[]> cells = new ArrayList<>();
                for (int r = 0; r < size; r++) {
                    for (int c = 0; c < size; c++) {
                        cells.add(new int[]{r, c});
                    }
                }
                Collections.shuffle(cells, random);

                for (int i = 0; i < cells.size(); i++) {
                    Player p = i % 2 == 0 ? X : O;
                    int[] cell = cells.get(i);
                    board.place(cell[0], cell[1], p.symbol());
                    Move move = new Move(p, cell[0], cell[1]);

                    boolean expected = scan.isWinningMove(board, move);
                    assertEquals(expected, counter.isWinningMove(board, move),
                            "size " + size + ", game " + game + ", move " + i);
                    movesChecked++;
                    if (expected) {
                        break;
                    }
                }
            }
        }
        assertEquals(true, movesChecked > 50_000, "checked " + movesChecked + " moves");
    }

    @Test
    void counterStrategyCannotBeSharedAcrossBoardSizes() {
        WinningStrategy counter = new CounterWinningStrategy();
        Board small = new Board(3);
        small.place(0, 0, Symbol.X);
        counter.isWinningMove(small, new Move(X, 0, 0));

        Board big = new Board(4);
        big.place(0, 0, Symbol.X);
        assertThrows(IllegalStateException.class, () -> counter.isWinningMove(big, new Move(X, 0, 0)));
    }
}
