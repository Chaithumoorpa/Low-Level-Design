package com.lld.management.parkinglot.model;

import java.time.Duration;
import java.time.Instant;

/** Proof of payment handed out at the exit gate. */
public record Receipt(Ticket ticket, Instant exitTime, Duration duration, long feeCents,
                      String paymentReference, boolean lostTicket) {

    @Override
    public String toString() {
        return String.format("%s %s parked %dh%02dm, paid $%d.%02d%s (%s)", ticket.id(),
                ticket.vehicle().licensePlate(), duration.toHours(), duration.toMinutesPart(),
                feeCents / 100, feeCents % 100, lostTicket ? " [lost ticket]" : "", paymentReference);
    }
}
