package com.lld.ds.bloomfilter.filter;

import com.lld.ds.bloomfilter.core.BloomMath;
import com.lld.ds.bloomfilter.core.Funnel;
import com.lld.ds.bloomfilter.core.ProbabilisticSet;
import com.lld.ds.bloomfilter.hash.HashPositions;

import java.util.Objects;

/**
 * Classic Bloom filter: m bits and k hash positions per item.
 *
 * <ul>
 *   <li>{@code add}: set the k bits.</li>
 *   <li>{@code mightContain}: true only if all k bits are set. A single 0 bit proves the item was
 *       never added, so there are <b>no false negatives</b>.</li>
 * </ul>
 * Sized from the expected number of items and the target false positive rate. Thread-safe and
 * lock-free: adds use CAS on 64-bit words, and reads never block.
 */
public class BloomFilter<T> implements ProbabilisticSet<T> {

    private final Funnel<T> funnel;
    private final AtomicBitArray bits;
    private final int numHashes;
    private final long expectedItems;
    private final double targetFalsePositiveRate;

    private BloomFilter(Funnel<T> funnel, long numBits, int numHashes, long expectedItems, double targetRate) {
        this.funnel = Objects.requireNonNull(funnel, "funnel");
        this.bits = new AtomicBitArray(numBits);
        this.numHashes = numHashes;
        this.expectedItems = expectedItems;
        this.targetFalsePositiveRate = targetRate;
    }

    /** The usual entry point: "I expect about n items and accept a p chance of a false positive". */
    public static <T> BloomFilter<T> create(Funnel<T> funnel, long expectedItems, double falsePositiveRate) {
        long m = BloomMath.optimalNumBits(expectedItems, falsePositiveRate);
        int k = BloomMath.optimalNumHashes(expectedItems, m);
        return new BloomFilter<>(funnel, m, k, expectedItems, falsePositiveRate);
    }

    /** Explicit sizing, for experiments or when m and k come from elsewhere. */
    public static <T> BloomFilter<T> withSize(Funnel<T> funnel, long numBits, int numHashes) {
        if (numHashes < 1) {
            throw new IllegalArgumentException("numHashes must be at least 1");
        }
        return new BloomFilter<>(funnel, numBits, numHashes, -1, Double.NaN);
    }

    @Override
    public boolean add(T item) {
        boolean changed = false;
        for (long position : positionsOf(item)) {
            changed |= bits.set(position);
        }
        return changed;
    }

    @Override
    public boolean mightContain(T item) {
        for (long position : positionsOf(item)) {
            if (!bits.get(position)) {
                return false;                      // one clear bit: definitely never added
            }
        }
        return true;
    }

    @Override
    public double expectedFalsePositiveRate() {
        return BloomMath.falsePositiveRateFromFill(numHashes, bits.numBits(), bits.bitsSet());
    }

    @Override
    public long approximateCount() {
        return BloomMath.estimateCount(numHashes, bits.numBits(), bits.bitsSet());
    }

    /**
     * Merges another filter into this one (set union). Both must have the same m and k.
     * Useful for building filters in parallel (per shard or per day) and combining them.
     */
    public void unionWith(BloomFilter<T> other) {
        if (!isCompatible(other)) {
            throw new IllegalArgumentException("Filters must have the same number of bits and hash functions");
        }
        bits.unionWith(other.bits);
    }

    public boolean isCompatible(BloomFilter<T> other) {
        return other.bits.numBits() == bits.numBits() && other.numHashes == numHashes;
    }

    /** True once more items were added than the filter was sized for (its error rate is now above target). */
    public boolean isOverCapacity() {
        return expectedItems > 0 && approximateCount() > expectedItems;
    }

    public long numBits() {
        return bits.numBits();
    }

    public int numHashes() {
        return numHashes;
    }

    public long bitsSet() {
        return bits.bitsSet();
    }

    public long memoryBytes() {
        return bits.memoryBytes();
    }

    public long expectedItems() {
        return expectedItems;
    }

    public double targetFalsePositiveRate() {
        return targetFalsePositiveRate;
    }

    long[] positionsOf(T item) {
        Objects.requireNonNull(item, "item");
        return HashPositions.positions(funnel.toBytes(item), bits.numBits(), numHashes);
    }

    @Override
    public String toString() {
        return String.format("BloomFilter[m=%,d bits (%,d KB), k=%d, fill=%.1f%%, ~%,d items, fpp~%.4f%%]",
                numBits(), memoryBytes() / 1024, numHashes, 100.0 * bitsSet() / numBits(),
                approximateCount(), expectedFalsePositiveRate() * 100);
    }
}
