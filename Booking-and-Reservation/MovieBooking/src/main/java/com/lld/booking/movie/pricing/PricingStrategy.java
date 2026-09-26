package com.lld.booking.movie.pricing;

import com.lld.booking.movie.model.Seat;
import com.lld.booking.movie.model.SeatType;
import com.lld.booking.movie.model.Show;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;

/** Ticket price for a seat at a show (Strategy: cinemas price differently). */
public interface PricingStrategy {

    long price(Show show, Seat seat);

    /**
     * Base price per seat type; evening (18:00 or later) and weekend shows cost more.
     * Multipliers are in basis points (12,500 = ×1.25).
     */
    static PricingStrategy peakAware(Map<SeatType, Long> base, int eveningBps, int weekendBps, ZoneId zone) {
        Map<SeatType, Long> prices = new EnumMap<>(base);
        return (show, seat) -> {
            LocalDateTime local = LocalDateTime.ofInstant(show.startsAt(), zone);
            long bps = 10_000;
            if (local.getHour() >= 18) {
                bps = bps * eveningBps / 10_000;
            }
            DayOfWeek d = local.getDayOfWeek();
            if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) {
                bps = bps * weekendBps / 10_000;
            }
            return Math.floorDiv(prices.get(seat.type()) * bps + 5_000, 10_000);
        };
    }
}
