package com.lld.games.chess.piece;

import com.lld.games.chess.board.Board;
import com.lld.games.chess.model.Color;
import com.lld.games.chess.model.Move;
import com.lld.games.chess.model.PieceType;
import com.lld.games.chess.model.Position;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for all pieces. Each subclass knows only how IT moves ("pseudo-legal" moves).
 * Whether a move leaves the own king in check is decided once, centrally, by MoveGenerator.
 *
 * <p>Pieces are immutable and hold no position, so the 12 distinct pieces are shared
 * (Flyweight, see {@link PieceFactory}). Where a piece stands is the Board's business.
 */
public abstract class Piece {

    private final Color color;

    protected Piece(Color color) {
        this.color = color;
    }

    public Color getColor() {
        return color;
    }

    public abstract PieceType getType();

    /** Squares this piece attacks from {@code from}, ignoring whether its own king is safe. */
    public abstract List<Position> attackedSquares(Board board, Position from);

    /**
     * Moves allowed by this piece's movement rules. Default: any attacked square that is empty
     * or holds an enemy piece. Pawns and kings override this for their special moves.
     */
    public List<Move> pseudoLegalMoves(Board board, Position from) {
        List<Move> moves = new ArrayList<>();
        for (Position to : attackedSquares(board, from)) {
            Piece target = board.pieceAt(to);
            if (target == null || target.color != color) {
                moves.add(Move.of(from, to, this, target));
            }
        }
        return moves;
    }

    /** FEN-style symbol: uppercase for White, lowercase for Black. */
    public char symbol() {
        char c = getType().symbol();
        return color == Color.WHITE ? c : Character.toLowerCase(c);
    }

    // ------------------------------------------------------------- shared helpers for subclasses

    /** One step in each direction (king, knight). */
    protected static List<Position> step(Position from, int[][] offsets) {
        List<Position> result = new ArrayList<>(offsets.length);
        for (int[] d : offsets) {
            int f = from.file() + d[0];
            int r = from.rank() + d[1];
            if (Position.inBounds(f, r)) {
                result.add(new Position(f, r));
            }
        }
        return result;
    }

    /** Slide in each direction until the edge or the first occupied square (included). */
    protected static List<Position> slide(Board board, Position from, int[][] directions) {
        List<Position> result = new ArrayList<>();
        for (int[] d : directions) {
            int f = from.file() + d[0];
            int r = from.rank() + d[1];
            while (Position.inBounds(f, r)) {
                Position p = new Position(f, r);
                result.add(p);
                if (board.pieceAt(p) != null) {
                    break;
                }
                f += d[0];
                r += d[1];
            }
        }
        return result;
    }

    @Override
    public String toString() {
        return color.displayName() + " " + getType().name().toLowerCase();
    }
}
