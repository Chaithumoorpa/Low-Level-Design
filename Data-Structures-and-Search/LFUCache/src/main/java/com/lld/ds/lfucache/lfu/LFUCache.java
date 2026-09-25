package com.lld.ds.lfucache.lfu;

import com.lld.ds.lfucache.cache.Cache;
import com.lld.ds.lfucache.cache.CacheStats;
import com.lld.ds.lfucache.cache.EvictionListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Least Frequently Used cache with strict O(1) get, put and remove.
 *
 * <pre>
 *   index:          key -> Item
 *   frequency list: [f=1] <-> [f=3] <-> [f=7]       (ascending, only frequencies in use)
 *                     |         |          |
 *   item lists:     c, d      a          b          (each MRU ... LRU)
 * </pre>
 *
 * Each item points to its frequency node, and each frequency node points to its neighbours.
 * A hit moves the item to the node for {@code f + 1}, which is the next node or a new one
 * inserted right after, so no search is ever needed. The victim is the LRU item of the
 * first (lowest) frequency node. Ties between equal frequencies are broken by recency.
 *
 * <p>Optional <b>aging</b> halves every frequency every N operations, so items that were
 * popular long ago cannot occupy the cache forever.
 *
 * <p>Not thread-safe; guard it with a lock for concurrent use (see README).
 */
public class LFUCache<K, V> implements Cache<K, V> {

    /** A cached entry. Belongs to exactly one frequency node's item list. */
    private static final class Item<K, V> {
        final K key;
        V value;
        FreqNode<K, V> parent;
        Item<K, V> prev;
        Item<K, V> next;

        Item(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    /** All items with the same use count, in recency order (head side = most recent). */
    private static final class FreqNode<K, V> {
        final int freq;
        final Item<K, V> head = new Item<>(null, null);   // sentinels
        final Item<K, V> tail = new Item<>(null, null);
        FreqNode<K, V> prev;
        FreqNode<K, V> next;

        FreqNode(int freq) {
            this.freq = freq;
            head.next = tail;
            tail.prev = head;
        }

        void addFirst(Item<K, V> item) {
            item.parent = this;
            item.prev = head;
            item.next = head.next;
            head.next.prev = item;
            head.next = item;
        }

        void unlink(Item<K, V> item) {
            item.prev.next = item.next;
            item.next.prev = item.prev;
            item.prev = null;
            item.next = null;
        }

        Item<K, V> leastRecent() {
            return tail.prev;
        }

        boolean isEmpty() {
            return head.next == tail;
        }
    }

    private final int capacity;
    private final int agingInterval;                     // 0 = no aging
    private final EvictionListener<K, V> evictionListener;
    private final Map<K, Item<K, V>> index;
    private final FreqNode<K, V> first = new FreqNode<>(0);                  // sentinel before lowest
    private final FreqNode<K, V> last = new FreqNode<>(Integer.MAX_VALUE);   // sentinel after highest
    private long hits;
    private long misses;
    private long evictions;
    private int operationsSinceAging;

    public LFUCache(int capacity) {
        this(capacity, 0, (k, v) -> { });
    }

    private LFUCache(int capacity, int agingInterval, EvictionListener<K, V> evictionListener) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Capacity must be at least 1, got " + capacity);
        }
        if (agingInterval < 0) {
            throw new IllegalArgumentException("Aging interval must be >= 0, got " + agingInterval);
        }
        this.capacity = capacity;
        this.agingInterval = agingInterval;
        this.evictionListener = Objects.requireNonNull(evictionListener);
        this.index = new HashMap<>(capacity * 4 / 3 + 1);
        first.next = last;
        last.prev = first;
    }

    public static <K, V> Builder<K, V> builder() {
        return new Builder<>();
    }

    // ------------------------------------------------------------------ Cache API

    @Override
    public Optional<V> get(K key) {
        Objects.requireNonNull(key, "key");
        Item<K, V> item = index.get(key);
        if (item == null) {
            misses++;
            return Optional.empty();
        }
        hits++;
        V value = item.value;
        increment(item);
        countOperation();
        return Optional.of(value);
    }

    @Override
    public void put(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");

        Item<K, V> existing = index.get(key);
        if (existing != null) {
            existing.value = value;                // an update counts as a use
            increment(existing);
        } else {
            if (index.size() == capacity) {
                evict();
            }
            Item<K, V> item = new Item<>(key, value);
            FreqNode<K, V> ones = first.next.freq == 1 ? first.next : insertAfter(first, 1);
            ones.addFirst(item);
            index.put(key, item);
        }
        countOperation();
    }

    @Override
    public boolean remove(K key) {
        Objects.requireNonNull(key, "key");
        Item<K, V> item = index.remove(key);
        if (item == null) {
            return false;
        }
        detach(item);
        return true;
    }

    @Override
    public Optional<V> peek(K key) {
        Item<K, V> item = index.get(Objects.requireNonNull(key, "key"));
        return item == null ? Optional.empty() : Optional.of(item.value);
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
        first.next = last;
        last.prev = first;
        operationsSinceAging = 0;
    }

    @Override
    public CacheStats stats() {
        return new CacheStats(hits, misses, evictions);
    }

    // ------------------------------------------------------------------ O(1) core

    /** Moves an item from frequency f to f + 1. */
    private void increment(Item<K, V> item) {
        FreqNode<K, V> current = item.parent;
        int nextFreq = current.freq == Integer.MAX_VALUE - 1 ? current.freq : current.freq + 1;
        if (nextFreq == current.freq) {            // saturated: just refresh recency
            current.unlink(item);
            current.addFirst(item);
            return;
        }
        FreqNode<K, V> target = current.next.freq == nextFreq ? current.next : insertAfter(current, nextFreq);
        current.unlink(item);
        target.addFirst(item);
        if (current.isEmpty()) {
            removeNode(current);
        }
    }

    /** Victim = least recently used item among those with the lowest frequency. */
    private void evict() {
        FreqNode<K, V> lowest = first.next;
        Item<K, V> victim = lowest.leastRecent();
        index.remove(victim.key);
        detach(victim);
        evictions++;
        evictionListener.onEvict(victim.key, victim.value);
    }

    private void detach(Item<K, V> item) {
        FreqNode<K, V> node = item.parent;
        node.unlink(item);
        item.parent = null;
        if (node.isEmpty()) {
            removeNode(node);
        }
    }

    private FreqNode<K, V> insertAfter(FreqNode<K, V> anchor, int freq) {
        FreqNode<K, V> node = new FreqNode<>(freq);
        node.prev = anchor;
        node.next = anchor.next;
        anchor.next.prev = node;
        anchor.next = node;
        return node;
    }

    private void removeNode(FreqNode<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    // ------------------------------------------------------------------ aging

    private void countOperation() {
        if (agingInterval > 0 && ++operationsSinceAging >= agingInterval) {
            halveFrequencies();
            operationsSinceAging = 0;
        }
    }

    /**
     * Halves every item's frequency (minimum 1), so old popularity fades. O(n), but it runs only
     * once every {@code agingInterval} operations, so it is O(1) amortised when the interval is at
     * least the capacity. Relative order is preserved: items keep their frequency ranking, and
     * within a merged bucket, lower original frequency counts as less recent.
     */
    public void halveFrequencies() {
        List<Item<K, V>> ordered = new ArrayList<>(index.size());
        for (FreqNode<K, V> node = first.next; node != last; node = node.next) {
            for (Item<K, V> it = node.leastRecent(); it != node.head; it = it.prev) {
                ordered.add(it);                    // ascending frequency, LRU -> MRU inside each
            }
        }
        first.next = last;
        last.prev = first;

        FreqNode<K, V> tailNode = first;
        for (Item<K, V> item : ordered) {
            int newFreq = Math.max(1, item.parent.freq / 2);
            if (tailNode.freq != newFreq) {         // new frequencies arrive in non-decreasing order
                tailNode = insertAfter(tailNode, newFreq);
            }
            tailNode.addFirst(item);
        }
    }

    // ------------------------------------------------------------------ inspection

    /** How many times the key has been used (0 if absent). Does not count as a use. */
    public int frequencyOf(K key) {
        Item<K, V> item = index.get(key);
        return item == null ? 0 : item.parent.freq;
    }

    /** Frequency -> keys (most to least recent), lowest frequency first. For display and tests. */
    public Map<Integer, List<K>> frequencyBuckets() {
        Map<Integer, List<K>> view = new LinkedHashMap<>();
        for (FreqNode<K, V> node = first.next; node != last; node = node.next) {
            List<K> keys = new ArrayList<>();
            for (Item<K, V> it = node.head.next; it != node.tail; it = it.next) {
                keys.add(it.key);
            }
            view.put(node.freq, keys);
        }
        return view;
    }

    /** The key that would be evicted next, if any. */
    public Optional<K> nextVictim() {
        return first.next == last ? Optional.empty() : Optional.of(first.next.leastRecent().key);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        frequencyBuckets().forEach((freq, keys) -> sb.append("f=").append(freq).append(' ').append(keys).append("  "));
        return sb.length() == 0 ? "(empty)" : sb.toString().trim();
    }

    // ------------------------------------------------------------------ builder

    public static final class Builder<K, V> {
        private int capacity = 16;
        private int agingInterval;
        private EvictionListener<K, V> listener = (k, v) -> { };

        public Builder<K, V> capacity(int capacity) {
            this.capacity = capacity;
            return this;
        }

        /** Halve all frequencies every {@code operations} gets/puts. 0 disables aging. */
        public Builder<K, V> agingEvery(int operations) {
            this.agingInterval = operations;
            return this;
        }

        public Builder<K, V> evictionListener(EvictionListener<K, V> listener) {
            this.listener = listener;
            return this;
        }

        public LFUCache<K, V> build() {
            return new LFUCache<>(capacity, agingInterval, listener);
        }
    }
}
