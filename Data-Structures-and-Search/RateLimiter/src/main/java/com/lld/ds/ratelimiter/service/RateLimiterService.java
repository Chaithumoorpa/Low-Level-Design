package com.lld.ds.ratelimiter.service;

import com.lld.ds.ratelimiter.algorithm.Algorithm;
import com.lld.ds.ratelimiter.core.Decision;
import com.lld.ds.ratelimiter.core.RateLimitConfig;
import com.lld.ds.ratelimiter.core.RateLimiter;
import com.lld.ds.ratelimiter.core.TimeSource;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Facade used by an API gateway or filter: "may client C call endpoint E right now?".
 *
 * <p>Each endpoint can have its own rule (limit + algorithm); others fall back to a default rule.
 * Budgets are tracked per (client, endpoint), so a client hammering /search does not use up its
 * /checkout budget. Rules can be replaced at runtime (e.g. from a config service).
 */
public class RateLimiterService {

    private final TimeSource time;
    private final Map<String, RateLimiter> endpointRules = new ConcurrentHashMap<>();
    private volatile RateLimiter defaultRule;

    public RateLimiterService(Algorithm defaultAlgorithm, RateLimitConfig defaultConfig, TimeSource time) {
        this.time = Objects.requireNonNull(time);
        this.defaultRule = defaultAlgorithm.create(defaultConfig, time);
    }

    /** Adds or replaces the rule for one endpoint. Replacing resets that endpoint's counters. */
    public void setRule(String endpoint, Algorithm algorithm, RateLimitConfig config) {
        endpointRules.put(endpoint, algorithm.create(config, time));
    }

    /** Custom rule, e.g. a {@code CompositeRateLimiter}. */
    public void setRule(String endpoint, RateLimiter limiter) {
        endpointRules.put(endpoint, Objects.requireNonNull(limiter));
    }

    public void setDefaultRule(Algorithm algorithm, RateLimitConfig config) {
        this.defaultRule = algorithm.create(config, time);
    }

    public Decision check(String clientId, String endpoint) {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(endpoint, "endpoint");
        return ruleFor(endpoint).tryAcquire(clientId + "|" + endpoint);
    }

    /** HTTP headers for a decision, using the matching rule's limit. */
    public Map<String, String> headers(String endpoint, Decision decision) {
        return decision.toHttpHeaders(ruleFor(endpoint).limit());
    }

    private RateLimiter ruleFor(String endpoint) {
        return endpointRules.getOrDefault(endpoint, defaultRule);
    }
}
