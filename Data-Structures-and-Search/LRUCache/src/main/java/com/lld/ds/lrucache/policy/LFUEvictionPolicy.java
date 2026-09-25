package com.lld.ds.lrucache.policy;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * Least Frequently Used in O(1), with least-recently-used as the tie-breaker.
 *
 * <pre>
 *   frequency:  key  -> how many times it was used
 *   buckets:    freq -> keys with that frequency, in LRU order (LinkedHashSet)
 *   minFreq:    the smallest frequency that currently has keys
 * </pre>
 * An access moves a key from bucket f to bucket f+1. The victim is the oldest key in the
 * {@code minFreq} bucket.
 */
public class LFUEvictionPolicy<K> implements EvictionPolicy<K> {

    private final Map<K, Integer> frequency = new HashMap<>();
    private final Map<Integer, LinkedHashSet<K>> buckets = new HashMap<>();
    private int minFreq;

    @Override
    public void onInsert(K key) {
        frequency.put(key, 1);
        buckets.computeIfAbsent(1, f -> new LinkedHashSet<>()).add(key);
        minFreq = 1;                                   // a brand new key is always the least used
    }

    @Override
    public void onAccess(K key) {
        Integer freq = frequency.get(key);
        if (freq == null) {
            return;
        }
        LinkedHashSet<K> bucket = buckets.get(freq);
        bucket.remove(key);
        if (bucket.isEmpty()) {
            buckets.remove(freq);
            if (minFreq == freq) {
                minFreq = freq + 1;
            }
        }
        frequency.put(key, freq + 1);
        buckets.computeIfAbsent(freq + 1, f -> new LinkedHashSet<>()).add(key);
    }

    @Override
    public void onRemove(K key) {
        Integer freq = frequency.remove(key);
        if (freq == null) {
            return;
        }
        LinkedHashSet<K> bucket = buckets.get(freq);
        bucket.remove(key);
        if (bucket.isEmpty()) {
            buckets.remove(freq);          // minFreq may now point to an empty bucket; evict() skips ahead
        }
    }

    @Override
    public K evict() {
        LinkedHashSet<K> bucket = buckets.get(minFreq);
        while (bucket == null) {           // only after arbitrary removals; stays amortised O(1)
            bucket = buckets.get(++minFreq);
        }
        Iterator<K> it = bucket.iterator();
        K victim = it.next();
        it.remove();
        if (bucket.isEmpty()) {
            buckets.remove(minFreq);
        }
        frequency.remove(victim);
        return victim;
    }

    @Override
    public void clear() {
        frequency.clear();
        buckets.clear();
        minFreq = 0;
    }

    /** For tests and debugging. */
    public int frequencyOf(K key) {
        return frequency.getOrDefault(key, 0);
    }
}
