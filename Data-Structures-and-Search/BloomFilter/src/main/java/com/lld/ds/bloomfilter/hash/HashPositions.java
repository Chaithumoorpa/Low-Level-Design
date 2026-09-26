package com.lld.ds.bloomfilter.hash;

/**
 * Computes the k bit positions for an item using <b>double hashing</b>: two base hashes h1, h2 give
 * {@code g_i = h1 + i·h2 (mod m)} for i = 0..k-1. Proven to give the same false positive rate as k
 * independent hash functions (Kirsch and Mitzenmacher), while hashing the bytes only once.
 *
 * <p>h1 = FNV-1a 64-bit over the bytes, then a SplitMix64 finaliser to spread the bits.
 * h2 = a second finaliser pass with a different seed, forced odd so it cycles through all positions.
 */
public final class HashPositions {

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final long GOLDEN_GAMMA = 0x9e3779b97f4a7c15L;

    private HashPositions() {
    }

    /** The k bit positions in [0, numBits) for these bytes. */
    public static long[] positions(byte[] bytes, long numBits, int numHashes) {
        long h1 = mix64(fnv1a64(bytes));
        long h2 = mix64(h1 ^ GOLDEN_GAMMA) | 1L;
        long[] out = new long[numHashes];
        long combined = h1;
        for (int i = 0; i < numHashes; i++) {
            out[i] = Math.floorMod(combined, numBits);
            combined += h2;
        }
        return out;
    }

    static long fnv1a64(byte[] bytes) {
        long hash = FNV_OFFSET_BASIS;
        for (byte b : bytes) {
            hash ^= (b & 0xff);
            hash *= FNV_PRIME;
        }
        return hash;
    }

    /** SplitMix64 finaliser: turns a weak hash into well-mixed 64 bits. */
    static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
