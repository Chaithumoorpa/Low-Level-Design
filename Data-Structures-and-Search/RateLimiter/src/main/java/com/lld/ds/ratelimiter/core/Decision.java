package com.lld.ds.ratelimiter.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The answer for one request.
 *
 * @param allowed          whether the request may proceed
 * @param remaining        how many more requests would be allowed right now (best effort)
 * @param retryAfterMillis when denied: how long until a retry can succeed; 0 when allowed
 * @param delayMillis      leaky bucket only: how long the request should wait in the queue before
 *                         being processed (traffic shaping); 0 for every other algorithm
 */
public record Decision(boolean allowed, long remaining, long retryAfterMillis, long delayMillis) {

    public static Decision allow(long remaining) {
        return new Decision(true, Math.max(0, remaining), 0, 0);
    }

    public static Decision allowAfter(long remaining, long delayMillis) {
        return new Decision(true, Math.max(0, remaining), 0, delayMillis);
    }

    public static Decision deny(long retryAfterMillis) {
        return new Decision(false, 0, Math.max(1, retryAfterMillis), 0);
    }

    /** Standard HTTP headers a gateway would attach (Retry-After is in whole seconds). */
    public Map<String, String> toHttpHeaders(int limit) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-RateLimit-Limit", String.valueOf(limit));
        headers.put("X-RateLimit-Remaining", String.valueOf(remaining));
        if (!allowed) {
            headers.put("Retry-After", String.valueOf((retryAfterMillis + 999) / 1000));
        }
        return headers;
    }

    @Override
    public String toString() {
        if (!allowed) {
            return "DENY (retry in " + retryAfterMillis + " ms)";
        }
        return "ALLOW (" + remaining + " left" + (delayMillis > 0 ? ", wait " + delayMillis + " ms" : "") + ")";
    }
}
