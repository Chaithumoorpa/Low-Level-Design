package com.lld.social.learning.payment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** In-memory payments for the demo and tests; students in {@code declining} always get declined. */
public final class FakePayments implements PaymentPort {

    private final Set<String> declining = new HashSet<>();
    private final List<String> ledger = new ArrayList<>();
    private long seq;
    private long net;

    public synchronized void decline(String studentId) {
        declining.add(studentId);
    }

    @Override
    public synchronized String charge(String studentId, long amountCents, String description) {
        if (declining.contains(studentId)) {
            throw new PaymentDeclined("card declined for " + studentId);
        }
        String ref = "pay-" + (++seq);
        net += amountCents;
        ledger.add(ref + " charge " + amountCents + " " + studentId + " " + description);
        return ref;
    }

    @Override
    public synchronized void refund(String paymentReference, long amountCents) {
        net -= amountCents;
        ledger.add(paymentReference + " refund " + amountCents);
    }

    public synchronized long netCollected() {
        return net;
    }

    public synchronized List<String> ledger() {
        return List.copyOf(ledger);
    }
}
