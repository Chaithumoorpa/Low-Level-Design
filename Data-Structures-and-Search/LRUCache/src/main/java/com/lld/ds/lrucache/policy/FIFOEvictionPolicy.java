package com.lld.ds.lrucache.policy;

import java.util.Iterator;
import java.util.LinkedHashSet;

/** First In First Out: evicts the oldest inserted key; reads do not matter. */
public class FIFOEvictionPolicy<K> implements EvictionPolicy<K> {

    private final LinkedHashSet<K> insertionOrder = new LinkedHashSet<>();

    @Override
    public void onInsert(K key) {
        insertionOrder.add(key);
    }

    @Override
    public void onAccess(K key) {
        // FIFO ignores reads and updates
    }

    @Override
    public void onRemove(K key) {
        insertionOrder.remove(key);
    }

    @Override
    public K evict() {
        Iterator<K> oldest = insertionOrder.iterator();
        K victim = oldest.next();
        oldest.remove();
        return victim;
    }

    @Override
    public void clear() {
        insertionOrder.clear();
    }
}
