package com.lld.finance.payments.risk;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** A few common rules. Real systems add ML scores, device fingerprints, 3-D Secure challenges... */
public final class RiskRules {

    private RiskRules() {
    }

    /** Single payments above a limit need manual review. */
    public static RiskRule maxAmount(long maxMinor) {
        return ctx -> ctx.amount().minor() > maxMinor
                ? Optional.of("amount " + ctx.amount() + " above limit") : Optional.empty();
    }

    /** Cards reported stolen, by token. */
    public static RiskRule blocked(Set<String> fingerprints) {
        return ctx -> fingerprints.contains(ctx.instrument().fingerprint())
                ? Optional.of("instrument is blocked") : Optional.empty();
    }

    /** Card testing defence: at most {@code max} attempts per instrument per window. */
    public static RiskRule velocity(int max, Duration window) {
        Map<String, Deque<Instant>> attempts = new HashMap<>();
        return ctx -> {
            synchronized (attempts) {
                Deque<Instant> times = attempts.computeIfAbsent(ctx.instrument().fingerprint(), k -> new ArrayDeque<>());
                while (!times.isEmpty() && !times.peekFirst().isAfter(ctx.at().minus(window))) {
                    times.pollFirst();
                }
                if (times.size() >= max) {
                    return Optional.of("too many attempts with this instrument (" + max + " per " + window.toMinutes() + " min)");
                }
                times.addLast(ctx.at());
                return Optional.empty();
            }
        };
    }
}
