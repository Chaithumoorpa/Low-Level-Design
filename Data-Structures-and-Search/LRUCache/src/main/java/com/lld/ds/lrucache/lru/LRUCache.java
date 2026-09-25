package com.lld.ds.lrucache.lru;

import com.lld.ds.lrucache.cache.Cache;
import com.lld.ds.lrucache.cache.CacheStats;
import com.lld.ds.lrucache.cache.EvictionListener;
import com.lld.ds.lrucache.list.DoublyLinkedList;
import com.lld.ds.lrucache.list.DoublyLinkedList.Node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Least Recently Used cache with O(1) get and put.
 *
 * <pre>
 *   HashMap: key -> node          (find any entry in O(1))
 *   List:    MRU <-> ... <-> LRU  (reorder / evict in O(1))
 * </pre>
 *
 * Every get or put moves the entry to the front; when full, the entry at the back
 * (least recently used) is evicted.
 *
 * <p>Not thread-safe: wrap it in {@code SynchronizedCache} for concurrent use.
 */
public class LRUCache<K, V> implements Cache<K, V> {

    /** What the list stores: the key is needed to delete the map entry when evicting. */
    private static final class Entry<K, V> {
        private final K key;
        private V value;

        Entry(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private final int capacity;
    private final Map<K, Node<Entry<K, V>>> index;
    private final DoublyLinkedList<Entry<K, V>> recency = new DoublyLinkedList<>();
    private final EvictionListener<K, V> evictionListener;
    private long hits;
    private long misses;
    private long evictions;

    public LRUCache(int capacity) {
        this(capacity, (k, v) -> { });
    }

    public LRUCache(int capacity, EvictionListener<K, V> evictionListener) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Capacity must be at least 1, got " + capacity);
        }
        this.capacity = capacity;
        this.index = new HashMap<>(capacity * 4 / 3 + 1);   // sized to avoid rehashing
        this.evictionListener = Objects.requireNonNull(evictionListener);
    }

    @Override
    public Optional<V> get(K key) {
        Objects.requireNonNull(key, "key");
        Node<Entry<K, V>> node = index.get(key);
        if (node == null) {
            misses++;
            return Optional.empty();
        }
        recency.moveToFront(node);
        hits++;
        return Optional.of(node.item().value);
    }

    @Override
    public void put(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");

        Node<Entry<K, V>> existing = index.get(key);
        if (existing != null) {
            existing.item().value = value;          // update in place, refresh recency
            recency.moveToFront(existing);
            return;
        }
        if (index.size() == capacity) {
            evictLeastRecentlyUsed();
        }
        index.put(key, recency.addFirst(new Entry<>(key, value)));
    }

    private void evictLeastRecentlyUsed() {
        Entry<K, V> victim = recency.removeLast().item();
        index.remove(victim.key);
        evictions++;
        evictionListener.onEvict(victim.key, victim.value);
    }

    @Override
    public boolean remove(K key) {
        Objects.requireNonNull(key, "key");
        Node<Entry<K, V>> node = index.remove(key);
        if (node == null) {
            return false;
        }
        recency.remove(node);
        return true;
    }

    @Override
    public Optional<V> peek(K key) {
        Node<Entry<K, V>> node = index.get(Objects.requireNonNull(key, "key"));
        return node == null ? Optional.empty() : Optional.of(node.item().value);
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
        recency.clear();
    }

    @Override
    public CacheStats stats() {
        return new CacheStats(hits, misses, evictions);
    }

    /** Keys from most to least recently used. O(n); for display and tests. */
    public List<K> keysByRecency() {
        List<K> keys = new ArrayList<>(index.size());
        for (Entry<K, V> e : recency.toList()) {
            keys.add(e.key);
        }
        return keys;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[MRU] ");
        List<Entry<K, V>> entries = recency.toList();
        for (int i = 0; i < entries.size(); i++) {
            sb.append(entries.get(i).key).append('=').append(entries.get(i).value);
            if (i < entries.size() - 1) {
                sb.append(" -> ");
            }
        }
        return sb.append(" [LRU]").toString();
    }
}
