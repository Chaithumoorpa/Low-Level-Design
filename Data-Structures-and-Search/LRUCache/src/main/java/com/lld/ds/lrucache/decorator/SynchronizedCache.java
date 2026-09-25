package com.lld.ds.lrucache.decorator;

import com.lld.ds.lrucache.cache.Cache;
import com.lld.ds.lrucache.cache.CacheStats;

import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Decorator pattern: adds thread safety to ANY cache without changing it.
 *
 * <p>Why a single exclusive lock and not a ReadWriteLock? In an LRU cache {@code get} is a
 * <b>write</b>: it moves the entry to the front of the list. Two "readers" reordering the list
 * at the same time would corrupt it. See the README for sharding, the usual next step.
 */
public class SynchronizedCache<K, V> implements Cache<K, V> {

    private final Cache<K, V> delegate;
    private final ReentrantLock lock = new ReentrantLock();

    public SynchronizedCache(Cache<K, V> delegate) {
        this.delegate = delegate;
    }

    private <T> T locked(Supplier<T> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<V> get(K key) {
        return locked(() -> delegate.get(key));
    }

    @Override
    public void put(K key, V value) {
        locked(() -> {
            delegate.put(key, value);
            return null;
        });
    }

    @Override
    public boolean remove(K key) {
        return locked(() -> delegate.remove(key));
    }

    @Override
    public Optional<V> peek(K key) {
        return locked(() -> delegate.peek(key));
    }

    @Override
    public int size() {
        return locked(delegate::size);
    }

    @Override
    public int capacity() {
        return delegate.capacity();
    }

    @Override
    public void clear() {
        locked(() -> {
            delegate.clear();
            return null;
        });
    }

    @Override
    public CacheStats stats() {
        return locked(delegate::stats);
    }
}
