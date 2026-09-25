package com.lld.games.chess.render;

import com.lld.games.chess.board.Board;
import com.lld.games.chess.model.Position;
import com.lld.games.chess.piece.Piece;

/**
 * Prints the board from White's side: uppercase = White, lowercase = Black, '.' = empty.
 * Kept outside the model so a GUI or web view can replace it without touching game logic.
 */
public class ConsoleBoardRenderer {

    public String render(Board board) {
        StringBuilder sb = new StringBuilder();
        sb.append("    a b c d e f g h\n");
        sb.append("  +-----------------+\n");
        for (int rank = 7; rank >= 0; rank--) {
            sb.append(rank + 1).append(" | ");
            for (int file = 0; file < 8; file++) {
                Piece piece = board.pieceAt(new Position(file, rank));
                sb.append(piece == null ? '.' : piece.symbol()).append(' ');
            }
            sb.append("| ").append(rank + 1).append('\n');
        }
        sb.append("  +-----------------+\n");
        sb.append("    a b c d e f g h\n");
        return sb.toString();
    }
}
