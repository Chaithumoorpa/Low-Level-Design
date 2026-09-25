package com.lld.games.chess.piece;

import com.lld.games.chess.model.Color;
import com.lld.games.chess.model.PieceType;

import java.util.EnumMap;
import java.util.Map;

/**
 * Factory + Flyweight: the only place that knows which class implements which PieceType.
 * Pieces are immutable and position-free, so one shared instance per (color, type) is enough:
 * 12 objects serve every board, every copy and every game.
 */
public final class PieceFactory {

    private static final Map<Color, Map<PieceType, Piece>> CACHE = new EnumMap<>(Color.class);

    static {
        for (Color color : Color.values()) {
            Map<PieceType, Piece> byType = new EnumMap<>(PieceType.class);
            for (PieceType type : PieceType.values()) {
                byType.put(type, create(type, color));
            }
            CACHE.put(color, byType);
        }
    }

    private PieceFactory() {
    }

    public static Piece get(PieceType type, Color color) {
        return CACHE.get(color).get(type);
    }

    /** Parses a FEN symbol: 'N' = white knight, 'n' = black knight. */
    public static Piece fromSymbol(char symbol) {
        Color color = Character.isUpperCase(symbol) ? Color.WHITE : Color.BLACK;
        return get(PieceType.fromSymbol(symbol), color);
    }

    private static Piece create(PieceType type, Color color) {
        return switch (type) {
            case KING -> new King(color);
            case QUEEN -> new Queen(color);
            case ROOK -> new Rook(color);
            case BISHOP -> new Bishop(color);
            case KNIGHT -> new Knight(color);
            case PAWN -> new Pawn(color);
        };
    }
}
