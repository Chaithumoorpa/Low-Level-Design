package com.lld.games.chess.game;

import com.lld.games.chess.board.Board;
import com.lld.games.chess.board.MoveGenerator;
import com.lld.games.chess.exception.InvalidMoveException;
import com.lld.games.chess.listener.GameEventListener;
import com.lld.games.chess.model.Color;
import com.lld.games.chess.model.Move;
import com.lld.games.chess.model.PieceType;
import com.lld.games.chess.model.Player;
import com.lld.games.chess.model.Position;
import com.lld.games.chess.notation.Fen;
import com.lld.games.chess.piece.Piece;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Facade over a whole game: turns, legality, check/checkmate/draw detection, history and undo.
 *
 * <p>Undo uses the Memento pattern: before each move we save a snapshot of everything a move
 * can change. Restoring a snapshot is simpler and safer than writing an "unmake" for every
 * special move (castling, en passant, promotion, lost castling rights...).
 */
public class Game {

    /** Memento: all mutable game state before a move. */
    private record Snapshot(Board board, Color turn, int halfmoveClock, int fullmoveNumber,
                            Map<String, Integer> repetitions) {
    }

    private final Player white;
    private final Player black;
    private final MoveGenerator generator = new MoveGenerator();
    private final List<GameEventListener> listeners = new ArrayList<>();
    private final List<Move> history = new ArrayList<>();
    private final Deque<Snapshot> undoStack = new ArrayDeque<>();

    private Board board;
    private Color turn;
    private int halfmoveClock;     // moves since the last capture or pawn move (50-move rule)
    private int fullmoveNumber;
    private Map<String, Integer> repetitions = new HashMap<>();
    private GameStatus status = GameStatus.ACTIVE;
    private EndReason endReason;

    public Game(String whiteName, String blackName) {
        this(whiteName, blackName, Fen.START);
    }

    /** Start from any position, e.g. a puzzle or an endgame study. */
    public Game(String whiteName, String blackName, String fen) {
        this.white = new Player(whiteName, Color.WHITE);
        this.black = new Player(blackName, Color.BLACK);
        Fen.FenPosition start = Fen.parse(fen);
        this.board = start.board();
        this.turn = start.sideToMove();
        this.halfmoveClock = start.halfmoveClock();
        this.fullmoveNumber = start.fullmoveNumber();
        requireOneKingEach(board);
        recordPosition();
        evaluateStatus();
    }

    private static void requireOneKingEach(Board board) {
        for (Color color : Color.values()) {
            long kings = board.positionsOf(color).stream()
                    .filter(p -> board.pieceAt(p).getType() == PieceType.KING).count();
            if (kings != 1) {
                throw new IllegalArgumentException(color.displayName() + " must have exactly one king, found " + kings);
            }
        }
    }

    public void addListener(GameEventListener listener) {
        listeners.add(listener);
    }

    // ------------------------------------------------------------------ moves

    /** Accepts UCI text such as "e2e4" or "e7e8q" (promotion piece optional, default queen). */
    public Move makeMove(String uci) {
        String text = uci.trim().toLowerCase();
        if (text.length() != 4 && text.length() != 5) {
            throw new InvalidMoveException("Use from-square + to-square, e.g. e2e4 or e7e8q");
        }
        Position from = Position.of(text.substring(0, 2));
        Position to = Position.of(text.substring(2, 4));
        PieceType promotion = text.length() == 5 ? PieceType.fromSymbol(text.charAt(4)) : null;
        return makeMove(from, to, promotion);
    }

    public Move makeMove(Position from, Position to) {
        return makeMove(from, to, null);
    }

    public Move makeMove(Position from, Position to, PieceType promotion) {
        Move move = findLegalMove(from, to, promotion);

        undoStack.push(new Snapshot(board.copy(), turn, halfmoveClock, fullmoveNumber, new HashMap<>(repetitions)));
        board.apply(move);
        history.add(move);

        boolean resetsClock = move.isCapture() || move.piece().getType() == PieceType.PAWN;
        halfmoveClock = resetsClock ? 0 : halfmoveClock + 1;
        if (turn == Color.BLACK) {
            fullmoveNumber++;
        }
        turn = turn.opposite();
        recordPosition();

        listeners.forEach(l -> l.onMove(move));
        evaluateStatus();
        if (status == GameStatus.ACTIVE && board.isInCheck(turn)) {
            listeners.forEach(l -> l.onCheck(turn));
        }
        return move;
    }

    private Move findLegalMove(Position from, Position to, PieceType promotion) {
        ensureActive();
        Piece piece = board.pieceAt(from);
        if (piece == null) {
            throw new InvalidMoveException("No piece on " + from);
        }
        if (piece.getColor() != turn) {
            throw new InvalidMoveException("It is " + turn.displayName() + "'s turn");
        }

        PieceType wanted = promotion == null ? PieceType.QUEEN : promotion;
        for (Move m : generator.legalMovesFrom(board, from)) {
            if (m.to().equals(to) && (m.promotion() == null || m.promotion() == wanted)) {
                return m;
            }
        }

        // Explain WHY it failed: the piece can't move like that, or it would expose the king.
        boolean pieceCanReach = piece.pseudoLegalMoves(board, from).stream().anyMatch(m -> m.to().equals(to));
        throw new InvalidMoveException(pieceCanReach
                ? "Illegal: " + from + "-" + to + " would leave your king in check"
                : "Illegal: a " + piece.getType().name().toLowerCase() + " cannot move " + from + "-" + to);
    }

    /** Takes back the last move (the Memento restore). */
    public Move undo() {
        if (history.isEmpty()) {
            throw new InvalidMoveException("Nothing to undo");
        }
        Snapshot s = undoStack.pop();
        board = s.board();
        turn = s.turn();
        halfmoveClock = s.halfmoveClock();
        fullmoveNumber = s.fullmoveNumber();
        repetitions = s.repetitions();
        status = GameStatus.ACTIVE;
        endReason = null;

        Move undone = history.remove(history.size() - 1);
        listeners.forEach(l -> l.onUndo(undone));
        return undone;
    }

    public void resign(Color color) {
        ensureActive();
        finish(GameStatus.winFor(color.opposite()), EndReason.RESIGNATION);
    }

    /** Both players agreed to a draw (the offer/accept handshake is left to the UI). */
    public void agreeDraw() {
        ensureActive();
        finish(GameStatus.DRAW, EndReason.DRAW_AGREED);
    }

    // ------------------------------------------------------------------ end-of-game rules

    private void evaluateStatus() {
        if (!generator.hasAnyLegalMove(board, turn)) {
            if (board.isInCheck(turn)) {
                finish(GameStatus.winFor(turn.opposite()), EndReason.CHECKMATE);
            } else {
                finish(GameStatus.DRAW, EndReason.STALEMATE);
            }
        } else if (halfmoveClock >= 100) {
            finish(GameStatus.DRAW, EndReason.FIFTY_MOVE_RULE);
        } else if (repetitions.getOrDefault(Fen.positionKey(board, turn), 0) >= 3) {
            finish(GameStatus.DRAW, EndReason.THREEFOLD_REPETITION);
        } else if (InsufficientMaterial.isDraw(board)) {
            finish(GameStatus.DRAW, EndReason.INSUFFICIENT_MATERIAL);
        }
    }

    private void finish(GameStatus result, EndReason reason) {
        status = result;
        endReason = reason;
        listeners.forEach(l -> l.onGameOver(result, reason));
    }

    private void recordPosition() {
        repetitions.merge(Fen.positionKey(board, turn), 1, Integer::sum);
    }

    private void ensureActive() {
        if (status.isOver()) {
            throw new InvalidMoveException("Game is over: " + status + " by " + endReason);
        }
    }

    // ------------------------------------------------------------------ queries

    public List<Move> getLegalMoves() {
        return status.isOver() ? List.of() : generator.legalMoves(board, turn);
    }

    public List<Move> getLegalMovesFrom(Position from) {
        Piece piece = board.pieceAt(from);
        if (status.isOver() || piece == null || piece.getColor() != turn) {
            return List.of();
        }
        return generator.legalMovesFrom(board, from);
    }

    public boolean isInCheck() {
        return board.isInCheck(turn);
    }

    public Board getBoard() {
        return board;
    }

    public Color getTurn() {
        return turn;
    }

    public Player getCurrentPlayer() {
        return turn == Color.WHITE ? white : black;
    }

    public Player getPlayer(Color color) {
        return color == Color.WHITE ? white : black;
    }

    public GameStatus getStatus() {
        return status;
    }

    public Optional<EndReason> getEndReason() {
        return Optional.ofNullable(endReason);
    }

    public List<Move> getHistory() {
        return Collections.unmodifiableList(history);
    }

    public int getHalfmoveClock() {
        return halfmoveClock;
    }

    public String toFen() {
        return Fen.toFen(board, turn, halfmoveClock, fullmoveNumber);
    }
}
