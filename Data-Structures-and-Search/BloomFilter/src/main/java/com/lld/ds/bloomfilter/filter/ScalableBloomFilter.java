package com.lld.ds.bloomfilter.filter;

import com.lld.ds.bloomfilter.core.Funnel;
import com.lld.ds.bloomfilter.core.ProbabilisticSet;

import java.util.ArrayList;
import java.util.List;

/**
 * A Bloom filter for when the number of items is not known up front.
 *
 * <p>It is a chain of plain Bloom filters. When the newest one is full, a bigger one is added:
 * each has {@code growth}x the capacity of the previous one and a tighter error rate
 * ({@code p0, p0·r, p0·r², ...}). The overall false positive rate is at most the sum,
 * {@code p0 / (1 - r)}, so it stays bounded however many items arrive.
 * Lookups check every filter; adds go only to the newest.
 */
public class ScalableBloomFilter<T> implements ProbabilisticSet<T> {

    private final Funnel<T> funnel;
    private final double firstRate;
    private final double tighteningRatio;
    private final int growthFactor;
    private final List<BloomFilter<T>> filters = new ArrayList<>();
    private long itemsInCurrent;

    /**
     * @param initialCapacity items the first filter holds
     * @param targetRate      overall false positive bound, e.g. 0.01
     */
    public ScalableBloomFilter(Funnel<T> funnel, long initialCapacity, double targetRate) {
        this(funnel, initialCapacity, targetRate, 0.5, 2);
    }

    public ScalableBloomFilter(Funnel<T> funnel, long initialCapacity, double targetRate,
                               double tighteningRatio, int growthFactor) {
        if (!(tighteningRatio > 0 && tighteningRatio < 1) || growthFactor < 1) {
            throw new IllegalArgumentException("Need 0 < tighteningRatio < 1 and growthFactor >= 1");
        }
        this.funnel = funnel;
        this.tighteningRatio = tighteningRatio;
        this.growthFactor = growthFactor;
        this.firstRate = targetRate * (1 - tighteningRatio);   // so that the geometric sum equals targetRate
        filters.add(BloomFilter.create(funnel, initialCapacity, firstRate));
    }

    @Override
    public synchronized boolean add(T item) {
        if (mightContain(item)) {
            return false;                           // (probably) already present: don't use up capacity
        }
        BloomFilter<T> current = filters.get(filters.size() - 1);
        if (itemsInCurrent >= current.expectedItems()) {
            int n = filters.size();
            long capacity = current.expectedItems() * growthFactor;
            double rate = firstRate * Math.pow(tighteningRatio, n);
            current = BloomFilter.create(funnel, capacity, rate);
            filters.add(current);
            itemsInCurrent = 0;
        }
        current.add(item);
        itemsInCurrent++;
        return true;
    }

    @Override
    public synchronized boolean mightContain(T item) {
        for (BloomFilter<T> f : filters) {
            if (f.mightContain(item)) {
                return true;
            }
        }
        return false;
    }

    /** Upper bound: probability that at least one layer gives a false positive. */
    @Override
    public synchronized double expectedFalsePositiveRate() {
        double allClear = 1;
        for (BloomFilter<T> f : filters) {
            allClear *= 1 - f.expectedFalsePositiveRate();
        }
        return 1 - allClear;
    }

    @Override
    public synchronized long approximateCount() {
        long total = 0;
        for (BloomFilter<T> f : filters) {
            total += f.approximateCount();
        }
        return total;
    }

    public synchronized int layers() {
        return filters.size();
    }

    public synchronized long memoryBytes() {
        return filters.stream().mapToLong(BloomFilter::memoryBytes).sum();
    }
}
