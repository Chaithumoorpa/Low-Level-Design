package com.lld.ds.lrucache.policy;

/**
 * Strategy pattern: decides WHICH key leaves when the cache is full. The cache tells the policy
 * about every insert, access and removal; the policy keeps whatever bookkeeping it needs.
 * All operations should be O(1) so the cache stays O(1).
 */
public interface EvictionPolicy<K> {

    void onInsert(K key);

    void onAccess(K key);

    void onRemove(K key);

    /** Chooses a victim, forgets it, and returns it. Called only when the cache is non-empty. */
    K evict();

    void clear();
}
