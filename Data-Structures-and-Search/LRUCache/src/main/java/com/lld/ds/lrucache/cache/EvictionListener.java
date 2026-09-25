package com.lld.ds.lrucache.cache;

/**
 * Observer called when an entry is pushed out because the cache is full
 * (not on explicit {@code remove}). Typical uses: write-back to a database, metrics, logging.
 */
@FunctionalInterface
public interface EvictionListener<K, V> {

    void onEvict(K key, V value);
}
