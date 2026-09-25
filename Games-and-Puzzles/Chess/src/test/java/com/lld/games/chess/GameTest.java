package com.lld.games.chess;

import com.lld.games.chess.exception.InvalidMoveException;
import com.lld.games.chess.game.EndReason;
import com.lld.games.chess.game.Game;
import com.lld.games.chess.game.GameStatus;
import com.lld.games.chess.listener.GameEventListener;
import com.lld.games.chess.model.Color;
import com.lld.games.chess.model.Move;
import com.lld.games.chess.model.MoveType;
import com.lld.games.chess.model.PieceType;
import com.lld.games.chess.model.Position;
import com.lld.games.chess.notation.Fen;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameTest {

    private static Game fromFen(String fen) {
        return new Game("W", "B", fen);
    }

    private static void play(Game game, String... moves) {
        for (String m : moves) {
            game.makeMove(m);
        }
    }

    private static PieceType typeAt(Game game, String square) {
        var piece = game.getBoard().pieceAt(Position.of(square));
        return piece == null ? null : piece.getType();
    }

    // ------------------------------------------------------------------ basics

    @Test
    void newGameHasTwentyLegalMovesAndWhiteToMove() {
        Game game = new Game("Alice", "Bob");

        assertEquals(Color.WHITE, game.getTurn());
        assertEquals(20, game.getLegalMoves().size());
        assertEquals("Alice", game.getCurrentPlayer().name());
    }

    @Test
    void turnsAlternate() {
        Game game = new Game("W", "B");
        game.makeMove("e2e4");

        assertEquals(Color.BLACK, game.getTurn());
        assertThrows(InvalidMoveException.class, () -> game.makeMove("d2d4"));
    }

    @Test
    void illegalPieceMovementIsRejectedWithReason() {
        Game game = new Game("W", "B");

        InvalidMoveException e = assertThrows(InvalidMoveException.class, () -> game.makeMove("g1g3"));
        assertTrue(e.getMessage().contains("knight cannot move"));
    }

    @Test
    void emptySquareIsRejected() {
        Game game = new Game("W", "B");

        assertThrows(InvalidMoveException.class, () -> game.makeMove("e4e5"));
    }

    @Test
    void pinnedPieceCannotMove() {
        // White knight on e2 is pinned to the king on e1 by the rook on e8.
        Game game = fromFen("4r1k1/8/8/8/8/8/4N3/4K3 w - - 0 1");

        InvalidMoveException e = assertThrows(InvalidMoveException.class, () -> game.makeMove("e2c3"));
        assertTrue(e.getMessage().contains("would leave your king in check"));
    }

    @Test
    void kingCannotMoveIntoCheck() {
        Game game = fromFen("4k3/8/8/8/8/8/3r4/4K3 w - - 0 1");

        assertThrows(InvalidMoveException.class, () -> game.makeMove("e1d1")); // attacked by rook
        game.makeMove("e1d2");                                                  // capture instead
        assertEquals(PieceType.KING, typeAt(game, "d2"));
    }

    @Test
    void mustRespondToCheck() {
        // Black queen on h1 checks along the first rank; nothing can block or capture it.
        Game game = fromFen("4k3/8/8/8/8/8/P7/4K2q w - - 0 1");

        assertTrue(game.isInCheck());
        assertThrows(InvalidMoveException.class, () -> game.makeMove("a2a3"));
        assertTrue(game.getLegalMoves().stream().allMatch(m -> m.piece().getType() == PieceType.KING));
    }

    // ------------------------------------------------------------------ special moves

    @Test
    void kingsideAndQueensideCastling() {
        Game game = fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");

        Move m = game.makeMove("e1g1");
        assertEquals(MoveType.CASTLE_KINGSIDE, m.type());
        assertEquals(PieceType.ROOK, typeAt(game, "f1"));
        assertNull(typeAt(game, "h1"));

        game.makeMove("e8c8");
        assertEquals(PieceType.KING, typeAt(game, "c8"));
        assertEquals(PieceType.ROOK, typeAt(game, "d8"));
    }

    @Test
    void cannotCastleThroughAttackedSquare() {
        // Black rook on f8 attacks f1, which the king would pass over.
        Game game = fromFen("4kr2/8/8/8/8/8/8/4K2R w K - 0 1");

        assertThrows(InvalidMoveException.class, () -> game.makeMove("e1g1"));
    }

    @Test
    void cannotCastleOutOfCheck() {
        Game game = fromFen("4k3/8/8/8/8/8/4r3/R3K2R w KQ - 0 1");

        assertThrows(InvalidMoveException.class, () -> game.makeMove("e1g1"));
        assertThrows(InvalidMoveException.class, () -> game.makeMove("e1c1"));
    }

    @Test
    void movingRookLosesThatCastlingRight() {
        Game game = fromFen("4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1");
        play(game, "h1h2", "e8e7", "h2h1", "e7e8");

        assertThrows(InvalidMoveException.class, () -> game.makeMove("e1g1"));
        game.makeMove("e1c1"); // queenside right is untouched
    }

    @Test
    void capturingRookOnItsCornerRemovesOpponentsCastlingRight() {
        // White bishop takes the rook on h8: Black may no longer castle kingside, queenside is fine.
        Game game = fromFen("r3k2r/8/8/8/8/8/1B6/4K3 w kq - 0 1");
        game.makeMove("b2h8");

        assertThrows(InvalidMoveException.class, () -> game.makeMove("e8g8"));
        game.makeMove("e8c8");
    }

    @Test
    void enPassantCaptureRemovesThePassedPawn() {
        Game game = new Game("W", "B");
        play(game, "e2e4", "a7a6", "e4e5", "d7d5");

        Move m = game.makeMove("e5d6");

        assertEquals(MoveType.EN_PASSANT, m.type());
        assertNull(typeAt(game, "d5"));
        assertEquals(PieceType.PAWN, typeAt(game, "d6"));
    }

    @Test
    void enPassantOnlyImmediatelyAfterDoublePush() {
        Game game = new Game("W", "B");
        play(game, "e2e4", "a7a6", "e4e5", "d7d5", "h2h3", "h7h6");

        assertThrows(InvalidMoveException.class, () -> game.makeMove("e5d6"));
    }

    @Test
    void enPassantThatExposesKingIsIllegal() {
        // Famous trap: taking en passant removes both pawns from rank 5, opening the rook's line.
        Game game = fromFen("8/8/8/KPp4r/8/8/8/7k w - c6 0 1");

        assertThrows(InvalidMoveException.class, () -> game.makeMove("b5c6"));
    }

    @Test
    void promotionDefaultsToQueenAndSupportsUnderpromotion() {
        Game queen = fromFen("8/4P3/8/8/8/8/k7/4K3 w - - 0 1");
        queen.makeMove("e7e8");
        assertEquals(PieceType.QUEEN, typeAt(queen, "e8"));

        Game knight = fromFen("8/4P3/8/8/8/8/k7/4K3 w - - 0 1");
        knight.makeMove("e7e8n");
        assertEquals(PieceType.KNIGHT, typeAt(knight, "e8"));
    }

    // ------------------------------------------------------------------ game endings

    @Test
    void foolsMateIsCheckmate() {
        Game game = new Game("W", "B");
        play(game, "f2f3", "e7e5", "g2g4", "d8h4");

        assertEquals(GameStatus.BLACK_WON, game.getStatus());
        assertEquals(EndReason.CHECKMATE, game.getEndReason().orElseThrow());
        assertTrue(game.getLegalMoves().isEmpty());
        assertThrows(InvalidMoveException.class, () -> game.makeMove("a2a3"));
    }

    @Test
    void scholarsMateIsCheckmate() {
        Game game = new Game("W", "B");
        play(game, "e2e4", "e7e5", "f1c4", "b8c6", "d1h5", "g8f6", "h5f7");

        assertEquals(GameStatus.WHITE_WON, game.getStatus());
    }

    @Test
    void stalemateIsADraw() {
        // Black king on a8 has no moves and is not in check after Qc7.
        Game game = fromFen("k7/8/1K6/8/8/8/8/2Q5 w - - 0 1");
        game.makeMove("c1c7");

        assertEquals(GameStatus.DRAW, game.getStatus());
        assertEquals(EndReason.STALEMATE, game.getEndReason().orElseThrow());
    }

    @Test
    void fiftyMoveRuleDraws() {
        Game game = fromFen("4k3/8/8/8/8/8/8/R3K3 w - - 99 80");
        game.makeMove("a1a2");

        assertEquals(EndReason.FIFTY_MOVE_RULE, game.getEndReason().orElseThrow());
    }

    @Test
    void pawnMoveResetsFiftyMoveClock() {
        Game game = fromFen("4k3/8/8/8/8/8/P7/4K3 w - - 99 80");
        game.makeMove("a2a3");

        assertEquals(0, game.getHalfmoveClock());
        assertEquals(GameStatus.ACTIVE, game.getStatus());
    }

    @Test
    void threefoldRepetitionDraws() {
        Game game = new Game("W", "B");
        // Start position occurs 1x; each knight round trip brings it back.
        play(game, "g1f3", "g8f6", "f3g1", "f6g8");   // 2nd time
        play(game, "g1f3", "g8f6", "f3g1");
        assertEquals(GameStatus.ACTIVE, game.getStatus());
        game.makeMove("f6g8");                        // 3rd time

        assertEquals(EndReason.THREEFOLD_REPETITION, game.getEndReason().orElseThrow());
    }

    @Test
    void insufficientMaterialDraws() {
        assertEquals(EndReason.INSUFFICIENT_MATERIAL,
                fromFen("4k3/8/8/8/8/8/8/4K3 w - - 0 1").getEndReason().orElseThrow());
        assertEquals(EndReason.INSUFFICIENT_MATERIAL,
                fromFen("4k3/8/8/8/8/8/8/2B1K3 w - - 0 1").getEndReason().orElseThrow());
        assertEquals(EndReason.INSUFFICIENT_MATERIAL,
                fromFen("4k3/8/8/8/8/8/8/1N2K3 w - - 0 1").getEndReason().orElseThrow());
        // Bishops on the same square color (c1 and f4 are both dark)
        assertEquals(EndReason.INSUFFICIENT_MATERIAL,
                fromFen("4k3/8/8/8/5b2/8/8/2B1K3 w - - 0 1").getEndReason().orElseThrow());
        // A pawn, or bishops on opposite colors, can still mate
        assertEquals(GameStatus.ACTIVE, fromFen("4k3/8/8/8/8/8/P7/4K3 w - - 0 1").getStatus());
        assertEquals(GameStatus.ACTIVE, fromFen("4k3/8/8/8/8/5b2/8/2B1K3 w - - 0 1").getStatus());
    }

    @Test
    void captureIntoInsufficientMaterialEndsGame() {
        assertEquals(GameStatus.ACTIVE, fromFen("4k3/8/8/8/8/8/r7/4K3 w - - 0 1").getStatus()); // K+R vs K

        Game capture = fromFen("4k3/8/8/8/8/8/3r4/4K3 w - - 0 1");
        capture.makeMove("e1d2");
        assertEquals(EndReason.INSUFFICIENT_MATERIAL, capture.getEndReason().orElseThrow());
    }

    @Test
    void resignAndAgreedDraw() {
        Game resigned = new Game("W", "B");
        resigned.resign(Color.WHITE);
        assertEquals(GameStatus.BLACK_WON, resigned.getStatus());
        assertEquals(EndReason.RESIGNATION, resigned.getEndReason().orElseThrow());

        Game drawn = new Game("W", "B");
        drawn.agreeDraw();
        assertEquals(EndReason.DRAW_AGREED, drawn.getEndReason().orElseThrow());
    }

    // ------------------------------------------------------------------ undo, events, FEN

    @Test
    void undoRestoresPositionIncludingSpecialMoves() {
        Game game = fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
        String before = game.toFen();

        game.makeMove("e1g1");
        game.undo();

        assertEquals(before, game.toFen());
        assertEquals(Color.WHITE, game.getTurn());
        game.makeMove("e1c1"); // rights were restored too
    }

    @Test
    void undoAfterCheckmateReopensTheGame() {
        Game game = new Game("W", "B");
        play(game, "f2f3", "e7e5", "g2g4", "d8h4");

        game.undo();

        assertEquals(GameStatus.ACTIVE, game.getStatus());
        assertEquals(Color.BLACK, game.getTurn());
        assertThrows(InvalidMoveException.class, new Game("W", "B")::undo);
    }

    @Test
    void listenersSeeMoveCheckAndGameOver() {
        List<String> events = new ArrayList<>();
        Game game = new Game("W", "B");
        game.addListener(new GameEventListener() {
            @Override
            public void onMove(Move move) {
                events.add("move");
            }

            @Override
            public void onCheck(Color colorInCheck) {
                events.add("check:" + colorInCheck);
            }

            @Override
            public void onGameOver(GameStatus status, EndReason reason) {
                events.add("over:" + reason);
            }
        });

        play(game, "e2e4", "f7f6", "d2d4", "g7g5", "d1h5");

        assertEquals(List.of("move", "move", "move", "move", "move", "over:CHECKMATE"), events);
    }

    @Test
    void checkEventFiresWhenNotMate() {
        List<Color> checks = new ArrayList<>();
        Game game = new Game("W", "B");
        game.addListener(new GameEventListener() {
            @Override
            public void onCheck(Color colorInCheck) {
                checks.add(colorInCheck);
            }
        });

        play(game, "e2e4", "f7f5", "d1h5");

        assertEquals(List.of(Color.BLACK), checks);
    }

    @Test
    void fenRoundTrip() {
        String fen = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1";
        assertEquals(fen, fromFen(fen).toFen());

        Game game = new Game("W", "B");
        game.makeMove("e2e4");
        assertEquals("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1", game.toFen());
    }

    @Test
    void fenRejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> Fen.parse("8/8/8 w - - 0 1"));
        assertThrows(IllegalArgumentException.class, () -> fromFen("8/8/8/8/8/8/8/8 w - - 0 1")); // no kings
    }
}
