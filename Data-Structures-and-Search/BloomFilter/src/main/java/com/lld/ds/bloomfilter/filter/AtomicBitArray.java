package com.lld.ds.bloomfilter.filter;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Fixed-size bit array packed into 64-bit words, safe for concurrent use without locks.
 * Setting a bit uses compare-and-set on its word; bits are never cleared, so a reader can only ever
 * see "more bits set", which is exactly the guarantee a Bloom filter needs.
 */
final class AtomicBitArray {

    private final long numBits;
    private final AtomicLongArray words;
    private final AtomicLong bitsSet = new AtomicLong();

    AtomicBitArray(long numBits) {
        if (numBits < 1 || (numBits + 63) / 64 > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Unsupported bit array size: " + numBits);
        }
        this.numBits = numBits;
        this.words = new AtomicLongArray((int) ((numBits + 63) / 64));
    }

    /** @return true if the bit was 0 before (this call changed it) */
    boolean set(long index) {
        int word = (int) (index >>> 6);
        long mask = 1L << index;                       // Java uses the low 6 bits of the shift count
        while (true) {
            long current = words.get(word);
            if ((current & mask) != 0) {
                return false;
            }
            if (words.compareAndSet(word, current, current | mask)) {
                bitsSet.incrementAndGet();
                return true;
            }
        }
    }

    boolean get(long index) {
        return (words.get((int) (index >>> 6)) & (1L << index)) != 0;
    }

    /** OR another array of the same size into this one. */
    void unionWith(AtomicBitArray other) {
        for (int i = 0; i < words.length(); i++) {
            long add = other.words.get(i);
            while (true) {
                long current = words.get(i);
                long merged = current | add;
                if (merged == current || words.compareAndSet(i, current, merged)) {
                    bitsSet.addAndGet(Long.bitCount(merged) - Long.bitCount(current));
                    break;
                }
            }
        }
    }

    long numBits() {
        return numBits;
    }

    long bitsSet() {
        return bitsSet.get();
    }

    long memoryBytes() {
        return (long) words.length() * Long.BYTES;
    }
}
