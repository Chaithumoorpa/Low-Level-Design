package com.lld.ds.lfucache.tree;

import com.lld.ds.lfucache.cache.Cache;
import com.lld.ds.lfucache.cache.CacheStats;
import com.lld.ds.lfucache.cache.EvictionListener;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The natural FIRST answer in an interview: keep entries in a sorted set ordered by
 * (frequency, last-used time), so the victim is always {@code first()}.
 *
 * <p>Simple and obviously correct, but every access removes and re-inserts the entry into the
 * tree: O(log n). {@link com.lld.ds.lfucache.lfu.LFUCache} gets the same behaviour in O(1).
 * Both use the same tie-break (least recently used among equal frequencies), which lets the
 * tests compare them operation by operation.
 */
public class TreeLFUCache<K, V> implements Cache<K, V> {

    private static final class Entry<K, V> {
        final K key;
        V value;
        int freq;
        long lastUsed;

        Entry(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private static final Comparator<Entry<?, ?>> EVICTION_ORDER =
            Comparator.<Entry<?, ?>>comparingInt(e -> e.freq).thenComparingLong(e -> e.lastUsed);

    private final int capacity;
    private final Map<K, Entry<K, V>> index = new HashMap<>();
    private final TreeSet<Entry<K, V>> order = new TreeSet<>(EVICTION_ORDER);
    private final EvictionListener<K, V> evictionListener;
    private long clock;                        // logical time; unique per touch, so no ties in the tree
    private long hits;
    private long misses;
    private long evictions;

    public TreeLFUCache(int capacity) {
        this(capacity, (k, v) -> { });
    }

    public TreeLFUCache(int capacity, EvictionListener<K, V> evictionListener) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Capacity must be at least 1, got " + capacity);
        }
        this.capacity = capacity;
        this.evictionListener = Objects.requireNonNull(evictionListener);
    }

    @Override
    public Optional<V> get(K key) {
        Entry<K, V> e = index.get(Objects.requireNonNull(key, "key"));
        if (e == null) {
            misses++;
            return Optional.empty();
        }
        hits++;
        touch(e);
        return Optional.of(e.value);
    }

    @Override
    public void put(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Entry<K, V> e = index.get(key);
        if (e != null) {
            e.value = value;
            touch(e);
            return;
        }
        if (index.size() == capacity) {
            Entry<K, V> victim = order.pollFirst();          // lowest freq, then least recent
            index.remove(victim.key);
            evictions++;
            evictionListener.onEvict(victim.key, victim.value);
        }
        e = new Entry<>(key, value);
        e.freq = 1;
        e.lastUsed = ++clock;
        index.put(key, e);
        order.add(e);
    }

    /** Re-sort after changing the fields the comparator uses: remove, update, re-add. O(log n). */
    private void touch(Entry<K, V> e) {
        order.remove(e);
        e.freq++;
        e.lastUsed = ++clock;
        order.add(e);
    }

    @Override
    public boolean remove(K key) {
        Entry<K, V> e = index.remove(Objects.requireNonNull(key, "key"));
        if (e == null) {
            return false;
        }
        order.remove(e);
        return true;
    }

    @Override
    public Optional<V> peek(K key) {
        Entry<K, V> e = index.get(Objects.requireNonNull(key, "key"));
        return e == null ? Optional.empty() : Optional.of(e.value);
    }

    @Override
    public int size() {
        return index.size();
    }

    @Override
    public int capacity() {
        return capacity;
    }

    @Override
    public void clear() {
        index.clear();
        order.clear();
    }

    @Override
    public CacheStats stats() {
        return new CacheStats(hits, misses, evictions);
    }

    public int frequencyOf(K key) {
        Entry<K, V> e = index.get(key);
        return e == null ? 0 : e.freq;
    }
}
