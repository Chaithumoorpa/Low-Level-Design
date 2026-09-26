package com.lld.ds.bloomfilter.core;

/**
 * The standard Bloom filter formulas (n = expected items, p = target false positive rate,
 * m = bits, k = hash functions, x = bits currently set).
 *
 * <pre>
 *   m     = ceil( -n · ln p / (ln 2)² )     bits needed
 *   k     = round( (m / n) · ln 2 )         hash functions that minimise the error
 *   fpp   = (1 - e^(-k·n/m))^k              expected false positive rate after n inserts
 *   n_est = -(m / k) · ln(1 - x / m)        items estimated from the bits set
 * </pre>
 */
public final class BloomMath {

    private static final double LN2 = Math.log(2);

    private BloomMath() {
    }

    public static long optimalNumBits(long expectedItems, double falsePositiveRate) {
        validate(expectedItems, falsePositiveRate);
        return (long) Math.ceil(-expectedItems * Math.log(falsePositiveRate) / (LN2 * LN2));
    }

    public static int optimalNumHashes(long expectedItems, long numBits) {
        return Math.max(1, (int) Math.round((double) numBits / expectedItems * LN2));
    }

    public static double falsePositiveRate(int numHashes, long numBits, long insertedItems) {
        return Math.pow(1 - Math.exp(-(double) numHashes * insertedItems / numBits), numHashes);
    }

    /** Same thing, computed from the actual share of bits set (more accurate for a live filter). */
    public static double falsePositiveRateFromFill(int numHashes, long numBits, long bitsSet) {
        return Math.pow((double) bitsSet / numBits, numHashes);
    }

    public static long estimateCount(int numHashes, long numBits, long bitsSet) {
        if (bitsSet >= numBits) {
            return Long.MAX_VALUE;                      // saturated: the estimate is meaningless
        }
        return Math.round(-(double) numBits / numHashes * Math.log(1 - (double) bitsSet / numBits));
    }

    private static void validate(long expectedItems, double falsePositiveRate) {
        if (expectedItems < 1) {
            throw new IllegalArgumentException("expectedItems must be at least 1, got " + expectedItems);
        }
        if (!(falsePositiveRate > 0 && falsePositiveRate < 1)) {
            throw new IllegalArgumentException("falsePositiveRate must be in (0, 1), got " + falsePositiveRate);
        }
    }
}
