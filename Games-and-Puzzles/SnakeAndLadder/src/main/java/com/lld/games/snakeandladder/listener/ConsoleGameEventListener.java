package com.lld.games.snakeandladder.listener;

import com.lld.games.snakeandladder.game.TurnResult;
import com.lld.games.snakeandladder.model.Player;
import com.lld.games.snakeandladder.model.Snake;

/** Prints a human-readable play-by-play to standard output. */
public class ConsoleGameEventListener implements GameEventListener {

    @Override
    public void onTurn(TurnResult r) {
        StringBuilder line = new StringBuilder()
                .append(String.format("%-8s rolled %2d : %3d", r.player().getName(), r.roll(), r.from()));

        if (r.overshot() && r.landedOn() == r.from()) {
            line.append(" -> stays (needs exact roll)");
        } else {
            line.append(" -> ").append(String.format("%3d", r.landedOn()));
            if (r.overshot()) {
                line.append(" (bounced back)");
            }
            r.triggeredEntity().ifPresent(e -> line
                    .append(e instanceof Snake ? "  bitten by " : "  climbed ")
                    .append(e)
                    .append(" -> ").append(r.to()));
        }
        System.out.println(line);
    }

    @Override
    public void onGameOver(Player winner) {
        System.out.println();
        System.out.println(">>> " + winner.getName() + " wins the game! <<<");
    }
}
