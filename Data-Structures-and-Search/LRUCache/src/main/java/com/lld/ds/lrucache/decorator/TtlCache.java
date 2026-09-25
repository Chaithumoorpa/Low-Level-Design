package com.lld.ds.lrucache.decorator;

import com.lld.ds.lrucache.cache.Cache;
import com.lld.ds.lrucache.cache.CacheStats;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Decorator that adds time-to-live expiry on top of any cache (LRU, LFU, ...).
 *
 * <p>Each value is stored together with its expiry time, inside the wrapped cache itself, so
 * when the inner cache evicts an entry its expiry data disappears with it (no leaks).
 * Expiry is <b>lazy</b>: an expired entry is dropped when someone reads it. The {@link Clock} is
 * injected so tests can move time forward instead of sleeping.
 */
public class TtlCache<K, V> implements Cache<K, V> {

    /** Value plus the moment it stops being valid. */
    public record Expiring<V>(V value, Instant expiresAt) {
    }

    private final Cache<K, Expiring<V>> delegate;
    private final Duration ttl;
    private final Clock clock;

    public TtlCache(Cache<K, Expiring<V>> delegate, Duration ttl, Clock clock) {
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("TTL must be positive");
        }
        this.delegate = Objects.requireNonNull(delegate);
        this.ttl = ttl;
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Optional<V> get(K key) {
        dropIfExpired(key);
        return delegate.get(key).map(Expiring::value);   // an expired key is now a normal miss
    }

    @Override
    public Optional<V> peek(K key) {
        return delegate.peek(key).filter(e -> !isExpired(e)).map(Expiring::value);
    }

    /** Inserting or updating a key (re)starts its TTL. */
    @Override
    public void put(K key, V value) {
        Objects.requireNonNull(value, "value");
        delegate.put(key, new Expiring<>(value, clock.instant().plus(ttl)));
    }

    @Override
    public boolean remove(K key) {
        return delegate.remove(key);
    }

    /** Entries currently held, including expired ones not yet read (lazy expiry). */
    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public int capacity() {
        return delegate.capacity();
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    /** Stats of the inner cache; reading an expired entry counts as a miss. */
    @Override
    public CacheStats stats() {
        return delegate.stats();
    }

    private void dropIfExpired(K key) {
        delegate.peek(key).filter(this::isExpired).ifPresent(e -> delegate.remove(key));
    }

    private boolean isExpired(Expiring<V> entry) {
        return !clock.instant().isBefore(entry.expiresAt());
    }
}
