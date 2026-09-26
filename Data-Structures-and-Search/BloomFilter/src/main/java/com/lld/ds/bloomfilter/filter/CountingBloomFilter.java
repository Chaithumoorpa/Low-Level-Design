package com.lld.ds.bloomfilter.filter;

import com.lld.ds.bloomfilter.core.BloomMath;
import com.lld.ds.bloomfilter.core.Funnel;
import com.lld.ds.bloomfilter.core.ProbabilisticSet;
import com.lld.ds.bloomfilter.hash.HashPositions;

import java.util.Arrays;
import java.util.Objects;

/**
 * Bloom filter that supports deletion: every position holds a small counter (4 bits, 0..15)
 * instead of a bit. Add increments the k counters, remove decrements them, and "might contain"
 * means all k counters are above zero.
 *
 * <p>Costs 4x the memory of a plain Bloom filter. Two safety rules keep it free of false negatives:
 * <ul>
 *   <li>A counter that reached 15 is <b>stuck</b> at 15 (it may stand for more than 15 items, so
 *       decrementing it could wrongly reach 0).</li>
 *   <li>{@code remove} refuses items the filter says are absent (removing something never added
 *       would corrupt other items' counters).</li>
 * </ul>
 * Synchronised: add and remove touch k counters that must change together.
 */
public class CountingBloomFilter<T> implements ProbabilisticSet<T> {

    private static final int MAX_COUNT = 15;

    private final Funnel<T> funnel;
    private final byte[] counters;                  // two 4-bit counters per byte
    private final long numCounters;
    private final int numHashes;
    private long nonZeroCounters;
    private long items;

    public CountingBloomFilter(Funnel<T> funnel, long expectedItems, double falsePositiveRate) {
        this.funnel = Objects.requireNonNull(funnel, "funnel");
        this.numCounters = BloomMath.optimalNumBits(expectedItems, falsePositiveRate);
        if ((numCounters + 1) / 2 > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("Filter too large");
        }
        this.numHashes = BloomMath.optimalNumHashes(expectedItems, numCounters);
        this.counters = new byte[(int) ((numCounters + 1) / 2)];
    }

    @Override
    public synchronized boolean add(T item) {
        boolean changed = false;
        for (long p : distinctPositions(item)) {
            int c = get(p);
            if (c == 0) {
                nonZeroCounters++;
                changed = true;
            }
            if (c < MAX_COUNT) {
                set(p, c + 1);
            }
        }
        items++;
        return changed;
    }

    /**
     * Removes one occurrence of the item.
     *
     * @return false (and changes nothing) if the item is definitely not in the filter
     */
    public synchronized boolean remove(T item) {
        long[] positions = distinctPositions(item);
        for (long p : positions) {
            if (get(p) == 0) {
                return false;
            }
        }
        for (long p : positions) {
            int c = get(p);
            if (c == MAX_COUNT) {
                continue;                            // saturated: we no longer know the true count
            }
            set(p, c - 1);
            if (c == 1) {
                nonZeroCounters--;
            }
        }
        items--;
        return true;
    }

    @Override
    public synchronized boolean mightContain(T item) {
        for (long p : distinctPositions(item)) {
            if (get(p) == 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public synchronized double expectedFalsePositiveRate() {
        return BloomMath.falsePositiveRateFromFill(numHashes, numCounters, nonZeroCounters);
    }

    /** Exact count of add() minus successful remove() calls (the filter can track it, unlike a plain one). */
    @Override
    public synchronized long approximateCount() {
        return items;
    }

    public synchronized int counterAt(long position) {
        return get(position);
    }

    public long numCounters() {
        return numCounters;
    }

    public int numHashes() {
        return numHashes;
    }

    public long memoryBytes() {
        return counters.length;
    }

    long[] distinctPositions(T item) {
        Objects.requireNonNull(item, "item");
        long[] raw = HashPositions.positions(funnel.toBytes(item), numCounters, numHashes);
        // The same position can appear twice for one item (rare); count it once so add/remove stay symmetric.
        return Arrays.stream(raw).distinct().toArray();
    }

    private int get(long position) {
        int b = counters[(int) (position >>> 1)] & 0xff;
        return (position & 1) == 0 ? b & 0x0f : b >>> 4;
    }

    private void set(long position, int value) {
        int idx = (int) (position >>> 1);
        int b = counters[idx] & 0xff;
        b = (position & 1) == 0 ? (b & 0xf0) | value : (b & 0x0f) | (value << 4);
        counters[idx] = (byte) b;
    }
}
