package com.lld.ds.lfucache.cache;

/** Immutable snapshot of cache effectiveness counters. */
public record CacheStats(long hits, long misses, long evictions) {

    public long requests() {
        return hits + misses;
    }

    /** Share of {@code get} calls that found the key, 0.0 when there were no requests. */
    public double hitRate() {
        return requests() == 0 ? 0.0 : (double) hits / requests();
    }

    @Override
    public String toString() {
        return String.format("hits=%d misses=%d evictions=%d hitRate=%.1f%%", hits, misses, evictions, hitRate() * 100);
    }
}
