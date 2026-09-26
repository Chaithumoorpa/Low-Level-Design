package com.lld.booking.movie.pricing;

import java.time.Duration;

/** How much of the ticket price comes back on cancellation, by time left before the show. */
@FunctionalInterface
public interface RefundPolicy {

    int refundPercent(Duration beforeShow);

    /** 24h+ before: full refund; 2-24h: half; under 2h: nothing. */
    static RefundPolicy standard() {
        return before -> before.compareTo(Duration.ofHours(24)) >= 0 ? 100
                : before.compareTo(Duration.ofHours(2)) >= 0 ? 50 : 0;
    }
}
