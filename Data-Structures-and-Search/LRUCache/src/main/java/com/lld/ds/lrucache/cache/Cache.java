package com.lld.ds.lrucache.cache;

import java.util.Optional;

/**
 * A bounded key-value store. When it is full, adding a new key evicts an existing one;
 * which one depends on the implementation's eviction policy.
 *
 * <p>Null keys and values are rejected, so {@code Optional.empty()} always means "not present".
 */
public interface Cache<K, V> {

    /** Returns the value and records the access (which may change eviction order). */
    Optional<V> get(K key);

    /** Inserts or updates. May evict another entry if the cache is full. */
    void put(K key, V value);

    /** @return true if the key was present */
    boolean remove(K key);

    /** Reads without counting as an access: no reordering and no stats. */
    Optional<V> peek(K key);

    /** Presence check that does NOT count as an access. */
    default boolean containsKey(K key) {
        return peek(key).isPresent();
    }

    int size();

    int capacity();

    void clear();

    CacheStats stats();
}
