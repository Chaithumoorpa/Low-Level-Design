package com.lld.booking.movie.payment;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory payments; users in {@code declining} are declined. Tracks the net amount collected. */
public final class FakePayments implements PaymentPort {

    private final Set<String> declining = new HashSet<>();
    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong net = new AtomicLong();
    private final AtomicLong charges = new AtomicLong();

    public synchronized void decline(String userId) {
        declining.add(userId);
    }

    public synchronized void allow(String userId) {
        declining.remove(userId);
    }

    @Override
    public String charge(String userId, long amountCents, String description) {
        synchronized (this) {
            if (declining.contains(userId)) {
                throw new Declined("card declined for " + userId);
            }
        }
        net.addAndGet(amountCents);
        charges.incrementAndGet();
        return "pay-" + seq.incrementAndGet();
    }

    @Override
    public void refund(String paymentReference, long amountCents) {
        net.addAndGet(-amountCents);
    }

    public long netCollected() {
        return net.get();
    }

    public long charges() {
        return charges.get();
    }
}
