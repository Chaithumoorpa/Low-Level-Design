package com.lld.ds.bloomfilter.core;

/**
 * A set that trades exactness for memory.
 *
 * <ul>
 *   <li>{@code mightContain} returning <b>false</b> is always correct: the item was never added.</li>
 *   <li>{@code mightContain} returning <b>true</b> means "probably added", wrong with a small, known
 *       probability (the false positive rate).</li>
 * </ul>
 */
public interface ProbabilisticSet<T> {

    /** @return true if the filter changed (the item was definitely not present before) */
    boolean add(T item);

    boolean mightContain(T item);

    /** Probability that {@code mightContain} returns true for an item never added, given the current fill. */
    double expectedFalsePositiveRate();

    /** Estimate of how many distinct items have been added. */
    long approximateCount();
}
