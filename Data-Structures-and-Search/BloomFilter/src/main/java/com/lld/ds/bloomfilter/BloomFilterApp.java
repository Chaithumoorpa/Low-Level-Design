package com.lld.ds.bloomfilter;

import com.lld.ds.bloomfilter.core.BloomMath;
import com.lld.ds.bloomfilter.core.Funnel;
import com.lld.ds.bloomfilter.filter.BloomFilter;
import com.lld.ds.bloomfilter.filter.CountingBloomFilter;
import com.lld.ds.bloomfilter.filter.ScalableBloomFilter;

/**
 * Demo: sizing, a "username taken?" check measured against theory, deletion with a counting
 * filter, and a scalable filter growing past its initial capacity.
 */
public class BloomFilterApp {

    public static void main(String[] args) {
        sizingTable();
        usernameCheck();
        countingFilter();
        scalableFilter();
    }

    private static void sizingTable() {
        System.out.println("1) Sizing for 1,000,000 items");
        System.out.println("   target fpp     bits        memory     hashes   bits/item");
        for (double p : new double[]{0.1, 0.01, 0.001, 0.0001}) {
            long m = BloomMath.optimalNumBits(1_000_000, p);
            int k = BloomMath.optimalNumHashes(1_000_000, m);
            System.out.printf("   %-10s %,12d  %,8d KB   %4d     %5.1f%n",
                    p * 100 + "%", m, (m + 7) / 8 / 1024, k, m / 1_000_000.0);
        }
        System.out.println("   (a HashSet<String> of 1M short usernames needs roughly 60-100 MB)");
    }

    private static void usernameCheck() {
        System.out.println();
        System.out.println("2) \"Is this username taken?\": 1,000,000 registered, target 1%");
        BloomFilter<String> taken = BloomFilter.create(Funnel.STRING, 1_000_000, 0.01);
        for (int i = 0; i < 1_000_000; i++) {
            taken.add("user_" + i);
        }
        int falseNegatives = 0;
        for (int i = 0; i < 1_000_000; i++) {
            if (!taken.mightContain("user_" + i)) {
                falseNegatives++;
            }
        }
        int falsePositives = 0;
        int probes = 1_000_000;
        for (int i = 0; i < probes; i++) {
            if (taken.mightContain("free_name_" + i)) {
                falsePositives++;
            }
        }
        System.out.println("   " + taken);
        System.out.println("   false negatives:            " + falseNegatives + " (always 0)");
        System.out.printf("   false positives measured:   %.3f%% of %,d unused names%n", 100.0 * falsePositives / probes, probes);
        System.out.printf("   false positives predicted:  %.3f%%%n", taken.expectedFalsePositiveRate() * 100);
        System.out.println("   -> on \"maybe taken\", check the database; on \"not taken\", skip the database entirely.");
    }

    private static void countingFilter() {
        System.out.println();
        System.out.println("3) Counting Bloom filter (supports delete)");
        CountingBloomFilter<String> sessions = new CountingBloomFilter<>(Funnel.STRING, 1000, 0.01);
        sessions.add("session-a");
        sessions.add("session-b");
        System.out.println("   contains session-a? " + sessions.mightContain("session-a"));
        System.out.println("   remove session-a:   " + sessions.remove("session-a"));
        System.out.println("   contains session-a? " + sessions.mightContain("session-a"));
        System.out.println("   contains session-b? " + sessions.mightContain("session-b") + "  (untouched)");
        System.out.println("   remove never-added: " + sessions.remove("session-zzz") + "  (refused, protects other items)");
    }

    private static void scalableFilter() {
        System.out.println();
        System.out.println("4) Scalable Bloom filter: starts with room for 1,000, target 1%, receives 100,000");
        ScalableBloomFilter<Integer> seen = new ScalableBloomFilter<>(Funnel.INTEGER, 1_000, 0.01);
        for (int i = 0; i < 100_000; i++) {
            seen.add(i);
            if (i + 1 == 1_000 || i + 1 == 10_000 || i + 1 == 100_000) {
                System.out.printf("   after %,7d items: %d layers, %,6d KB, fpp bound %.3f%%%n",
                        i + 1, seen.layers(), seen.memoryBytes() / 1024, seen.expectedFalsePositiveRate() * 100);
            }
        }
        int fp = 0;
        for (int i = 1_000_000; i < 1_100_000; i++) {
            if (seen.mightContain(i)) {
                fp++;
            }
        }
        System.out.printf("   measured false positives: %.3f%% (stays under the 1%% target)%n", fp / 1000.0);
    }
}
