package com.lld.finance.payments.processor;

import com.lld.finance.payments.model.Instrument;
import com.lld.finance.payments.model.Money;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory stand-in for a real processor, for the demo and tests. Can simulate outages and declines
 * (cards ending in "0002" are declined for insufficient funds, as test cards often are).
 */
public final class FakeProcessor implements ProcessorClient {

    private final String id;
    private final int cost;
    private final Set<String> brands;
    private final boolean upi;
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Long> authorizedMinor = new HashMap<>();
    private int outages;
    private int sequence;

    public FakeProcessor(String id, int costBasisPoints, Set<String> brands, boolean upi) {
        this.id = id;
        this.cost = costBasisPoints;
        this.brands = brands;
        this.upi = upi;
    }

    /** The next {@code n} calls time out. */
    public synchronized FakeProcessor failNext(int n) {
        outages += n;
        return this;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean supports(Instrument instrument) {
        if (instrument instanceof Instrument.Card c) {
            return brands.contains(c.brand());
        }
        return upi;
    }

    @Override
    public int costBasisPoints() {
        return cost;
    }

    @Override
    public synchronized AuthResponse authorize(String paymentId, Money amount, Instrument instrument, CardData card)
            throws ProcessorUnavailable {
        outage("authorize " + paymentId);
        if (card != null && card.pan().endsWith("0002")) {
            calls.add("authorize " + paymentId + " declined");
            return AuthResponse.declined("insufficient funds");
        }
        String ref = id + "-auth-" + (++sequence);
        authorizedMinor.put(ref, amount.minor());
        calls.add("authorize " + paymentId + " " + amount + " -> " + ref);
        return AuthResponse.approved(ref);
    }

    @Override
    public synchronized String capture(String authReference, Money amount) throws ProcessorUnavailable {
        outage("capture " + authReference);
        if (amount.minor() > authorizedMinor.getOrDefault(authReference, 0L)) {
            throw new IllegalStateException("capture above authorization");
        }
        calls.add("capture " + authReference + " " + amount);
        return id + "-cap-" + (++sequence);
    }

    @Override
    public synchronized String refund(String authReference, Money amount) throws ProcessorUnavailable {
        outage("refund " + authReference);
        calls.add("refund " + authReference + " " + amount);
        return id + "-ref-" + (++sequence);
    }

    @Override
    public synchronized void voidAuthorization(String authReference) throws ProcessorUnavailable {
        outage("void " + authReference);
        calls.add("void " + authReference);
    }

    private void outage(String what) throws ProcessorUnavailable {
        if (outages > 0) {
            outages--;
            calls.add(what + " TIMEOUT");
            throw new ProcessorUnavailable(id + " timed out");
        }
    }

    public List<String> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }
}
