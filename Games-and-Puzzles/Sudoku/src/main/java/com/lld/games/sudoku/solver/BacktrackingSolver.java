package com.lld.games.sudoku.solver;

import com.lld.games.sudoku.board.Board;

import java.util.Optional;
import java.util.Random;

/**
 * Depth-first backtracking with two classic speed-ups:
 * <ol>
 *   <li><b>Bit masks</b>: each row, column and box keeps a mask of used digits, so the candidates
 *       of a cell are {@code ~(row | col | box)}, which is a few CPU instructions.</li>
 *   <li><b>MRV (minimum remaining values)</b>: always branch on the empty cell with the fewest
 *       candidates. Cells with one candidate are filled without guessing, and dead ends
 *       (zero candidates) are found immediately.</li>
 * </ol>
 * With a {@link Random}, candidates are tried in random order: that is how the generator
 * produces a different full grid every time.
 */
public class BacktrackingSolver implements SudokuSolver {

    private final Random random;

    public BacktrackingSolver() {
        this(null);
    }

    /** @param random if non-null, candidate order is shuffled (used to generate random grids) */
    public BacktrackingSolver(Random random) {
        this.random = random;
    }

    @Override
    public Optional<int[][]> solve(Board board) {
        Search search = new Search(board, 1);
        search.run();
        return Optional.ofNullable(search.firstSolution);
    }

    @Override
    public int countSolutions(Board board, int limit) {
        Search search = new Search(board, limit);
        search.run();
        return search.solutions;
    }

    /** One search over a private copy of the grid. */
    private final class Search {

        private final int n;
        private final int boxRows;
        private final int boxCols;
        private final int[][] grid;
        private final int[] rowMask;
        private final int[] colMask;
        private final int[] boxMask;
        private final int allDigits;
        private final int limit;
        private int solutions;
        private int[][] firstSolution;
        private boolean contradictory;

        Search(Board board, int limit) {
            this.n = board.getSize();
            this.boxRows = board.getBoxRows();
            this.boxCols = board.getBoxCols();
            this.grid = board.toGrid();
            this.rowMask = new int[n];
            this.colMask = new int[n];
            this.boxMask = new int[n];
            this.allDigits = ((1 << (n + 1)) - 1) & ~1;   // bits 1..n
            this.limit = limit;

            for (int r = 0; r < n; r++) {
                for (int c = 0; c < n; c++) {
                    int v = grid[r][c];
                    if (v != 0) {
                        int bit = 1 << v;
                        int b = box(r, c);
                        if ((rowMask[r] & bit) != 0 || (colMask[c] & bit) != 0 || (boxMask[b] & bit) != 0) {
                            contradictory = true;   // the starting grid already breaks the rules
                        }
                        rowMask[r] |= bit;
                        colMask[c] |= bit;
                        boxMask[b] |= bit;
                    }
                }
            }
        }

        void run() {
            if (!contradictory) {
                search();
            }
        }

        private int box(int r, int c) {
            return (r / boxRows) * boxRows + (c / boxCols);
        }

        private boolean search() {
            // MRV: find the empty cell with the fewest candidates
            int bestR = -1;
            int bestC = -1;
            int bestMask = 0;
            int bestCount = Integer.MAX_VALUE;
            for (int r = 0; r < n && bestCount > 1; r++) {
                for (int c = 0; c < n; c++) {
                    if (grid[r][c] != 0) {
                        continue;
                    }
                    int mask = allDigits & ~(rowMask[r] | colMask[c] | boxMask[box(r, c)]);
                    int count = Integer.bitCount(mask);
                    if (count == 0) {
                        return false;                        // dead end
                    }
                    if (count < bestCount) {
                        bestCount = count;
                        bestR = r;
                        bestC = c;
                        bestMask = mask;
                        if (count == 1) {
                            break;
                        }
                    }
                }
            }

            if (bestR == -1) {                               // no empty cell: a solution
                solutions++;
                if (firstSolution == null) {
                    firstSolution = new int[n][];
                    for (int r = 0; r < n; r++) {
                        firstSolution[r] = grid[r].clone();
                    }
                }
                return solutions >= limit;                   // stop once we have enough
            }

            int[] digits = digitsOf(bestMask, bestCount);
            int b = box(bestR, bestC);
            for (int d : digits) {
                int bit = 1 << d;
                grid[bestR][bestC] = d;
                rowMask[bestR] |= bit;
                colMask[bestC] |= bit;
                boxMask[b] |= bit;

                boolean stop = search();

                grid[bestR][bestC] = 0;
                rowMask[bestR] &= ~bit;
                colMask[bestC] &= ~bit;
                boxMask[b] &= ~bit;
                if (stop) {
                    return true;
                }
            }
            return false;
        }

        private int[] digitsOf(int mask, int count) {
            int[] digits = new int[count];
            int i = 0;
            for (int d = 1; d <= n; d++) {
                if ((mask & (1 << d)) != 0) {
                    digits[i++] = d;
                }
            }
            if (random != null) {
                for (int k = digits.length - 1; k > 0; k--) {   // Fisher-Yates
                    int j = random.nextInt(k + 1);
                    int tmp = digits[k];
                    digits[k] = digits[j];
                    digits[j] = tmp;
                }
            }
            return digits;
        }
    }
}
