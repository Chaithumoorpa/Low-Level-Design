package com.lld.ds.lrucache.policy;

import com.lld.ds.lrucache.cache.Cache;
import com.lld.ds.lrucache.cache.CacheStats;
import com.lld.ds.lrucache.cache.EvictionListener;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A general bounded cache: storage is a HashMap, and WHICH entry to evict is delegated to an
 * {@link EvictionPolicy}. Swap LRU for LFU or FIFO without touching this class (Open/Closed).
 */
public class PolicyCache<K, V> implements Cache<K, V> {

    private final int capacity;
    private final Map<K, V> storage;
    private final EvictionPolicy<K> policy;
    private final EvictionListener<K, V> evictionListener;
    private long hits;
    private long misses;
    private long evictions;

    public PolicyCache(int capacity, EvictionPolicy<K> policy) {
        this(capacity, policy, (k, v) -> { });
    }

    public PolicyCache(int capacity, EvictionPolicy<K> policy, EvictionListener<K, V> evictionListener) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Capacity must be at least 1, got " + capacity);
        }
        this.capacity = capacity;
        this.storage = new HashMap<>(capacity * 4 / 3 + 1);
        this.policy = Objects.requireNonNull(policy);
        this.evictionListener = Objects.requireNonNull(evictionListener);
    }

    public static <K, V> PolicyCache<K, V> lru(int capacity) {
        return new PolicyCache<>(capacity, new LRUEvictionPolicy<>());
    }

    public static <K, V> PolicyCache<K, V> lfu(int capacity) {
        return new PolicyCache<>(capacity, new LFUEvictionPolicy<>());
    }

    public static <K, V> PolicyCache<K, V> fifo(int capacity) {
        return new PolicyCache<>(capacity, new FIFOEvictionPolicy<>());
    }

    @Override
    public Optional<V> get(K key) {
        Objects.requireNonNull(key, "key");
        V value = storage.get(key);
        if (value == null) {
            misses++;
            return Optional.empty();
        }
        hits++;
        policy.onAccess(key);
        return Optional.of(value);
    }

    @Override
    public void put(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (storage.containsKey(key)) {
            storage.put(key, value);
            policy.onAccess(key);
            return;
        }
        if (storage.size() == capacity) {
            K victim = policy.evict();
            V evicted = storage.remove(victim);
            evictions++;
            evictionListener.onEvict(victim, evicted);
        }
        storage.put(key, value);
        policy.onInsert(key);
    }

    @Override
    public boolean remove(K key) {
        Objects.requireNonNull(key, "key");
        if (storage.remove(key) == null) {
            return false;
        }
        policy.onRemove(key);
        return true;
    }

    @Override
    public Optional<V> peek(K key) {
        return Optional.ofNullable(storage.get(Objects.requireNonNull(key, "key")));
    }

    @Override
    public int size() {
        return storage.size();
    }

    @Override
    public int capacity() {
        return capacity;
    }

    @Override
    public void clear() {
        storage.clear();
        policy.clear();
    }

    @Override
    public CacheStats stats() {
        return new CacheStats(hits, misses, evictions);
    }
}
