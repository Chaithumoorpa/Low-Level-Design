package com.lld.games.chess;

import com.lld.games.chess.board.Board;
import com.lld.games.chess.board.MoveGenerator;
import com.lld.games.chess.model.Color;
import com.lld.games.chess.model.Move;
import com.lld.games.chess.notation.Fen;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Perft ("performance test") counts every legal move sequence to a fixed depth and compares the
 * total with numbers computed independently by many chess engines. If a single rule is wrong
 * (castling through check, en passant exposing the king, a missing under-promotion...), the
 * count is off. It is the standard correctness test for chess move generators.
 *
 * <p>Positions and expected counts are the widely published reference set
 * (see https://www.chessprogramming.org/Perft_Results).
 */
class PerftTest {

    private final MoveGenerator generator = new MoveGenerator();

    private long perft(Board board, Color side, int depth) {
        if (depth == 0) {
            return 1;
        }
        long nodes = 0;
        for (Move move : generator.legalMoves(board, side)) {
            if (depth == 1) {
                nodes++;
                continue;
            }
            Board next = board.copy();
            next.apply(move);
            nodes += perft(next, side.opposite(), depth - 1);
        }
        return nodes;
    }

    @ParameterizedTest(name = "{0} depth {2} = {3}")
    @CsvSource(delimiter = ';', value = {
            "start;       rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1;                1; 20",
            "start;       rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1;                2; 400",
            "start;       rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1;                3; 8902",
            "start;       rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1;                4; 197281",
            "kiwipete;    r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1;    1; 48",
            "kiwipete;    r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1;    2; 2039",
            "kiwipete;    r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1;    3; 97862",
            "position3;   8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1;                               4; 43238",
            "position4;   r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1;       3; 9467",
            "position5;   rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8;               3; 62379",
    })
    void perftMatchesReferenceCounts(String name, String fen, int depth, long expected) {
        Fen.FenPosition position = Fen.parse(fen);

        assertEquals(expected, perft(position.board(), position.sideToMove(), depth), name);
    }
}
