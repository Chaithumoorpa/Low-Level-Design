package com.lld.ds.bloomfilter;

import com.lld.ds.bloomfilter.core.BloomMath;
import com.lld.ds.bloomfilter.core.Funnel;
import com.lld.ds.bloomfilter.filter.BloomFilter;
import com.lld.ds.bloomfilter.filter.CountingBloomFilter;
import com.lld.ds.bloomfilter.filter.ScalableBloomFilter;
import com.lld.ds.bloomfilter.hash.HashPositions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BloomFilterTest {

    // ------------------------------------------------------------------ sizing maths

    @Test
    void sizingMatchesTheTextbookNumbers() {
        // 1M items at 1% → about 9.59 million bits (≈1.14 MiB) and 7 hash functions.
        long m = BloomMath.optimalNumBits(1_000_000, 0.01);
        assertEquals(9_585_059, m);
        assertEquals(7, BloomMath.optimalNumHashes(1_000_000, m));

        // Every 10x lower error rate costs about 4.8 more bits per item.
        long m2 = BloomMath.optimalNumBits(1_000_000, 0.001);
        assertEquals(4.79, (m2 - m) / 1_000_000.0, 0.01);
    }

    @Test
    void invalidParametersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.create(Funnel.STRING, 0, 0.01));
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.create(Funnel.STRING, 100, 0));
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.create(Funnel.STRING, 100, 1));
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.withSize(Funnel.STRING, 100, 0));
        assertThrows(NullPointerException.class, () -> BloomFilter.create(Funnel.STRING, 10, 0.1).add(null));
    }

    // ------------------------------------------------------------------ core guarantees

    @Test
    void neverAFalseNegative() {
        BloomFilter<String> filter = BloomFilter.create(Funnel.STRING, 50_000, 0.01);
        for (int i = 0; i < 50_000; i++) {
            filter.add("item-" + i);
        }
        for (int i = 0; i < 50_000; i++) {
            assertTrue(filter.mightContain("item-" + i), "false negative for item-" + i);
        }
    }

    @Test
    void neverAFalseNegativeEvenWhenOverfilled() {
        BloomFilter<Integer> filter = BloomFilter.create(Funnel.INTEGER, 100, 0.01);
        for (int i = 0; i < 10_000; i++) {                    // 100x over capacity
            filter.add(i);
        }
        for (int i = 0; i < 10_000; i++) {
            assertTrue(filter.mightContain(i));
        }
        assertTrue(filter.isOverCapacity());
        assertTrue(filter.expectedFalsePositiveRate() > 0.5, "an overfilled filter says yes to almost everything");
    }

    @Test
    void emptyFilterContainsNothing() {
        BloomFilter<String> filter = BloomFilter.create(Funnel.STRING, 1000, 0.01);

        assertFalse(filter.mightContain("anything"));
        assertEquals(0, filter.approximateCount());
        assertEquals(0.0, filter.expectedFalsePositiveRate());
    }

    @Test
    void addReportsWhetherTheFilterChanged() {
        BloomFilter<String> filter = BloomFilter.create(Funnel.STRING, 1000, 0.01);

        assertTrue(filter.add("x"));
        assertFalse(filter.add("x"));                         // all its bits were already set
    }

    /** Measured false positive rate must match the target, for several targets. */
    @ParameterizedTest
    @ValueSource(doubles = {0.1, 0.03, 0.01, 0.001})
    void measuredFalsePositiveRateMatchesTheTarget(double target) {
        int n = 100_000;
        BloomFilter<Long> filter = BloomFilter.create(Funnel.LONG, n, target);
        Random random = new Random(1);
        Set<Long> added = new HashSet<>();
        while (added.size() < n) {
            long v = random.nextLong();
            added.add(v);
            filter.add(v);
        }

        int probes = 400_000;
        int falsePositives = 0;
        int tested = 0;
        while (tested < probes) {
            long v = random.nextLong();
            if (added.contains(v)) {
                continue;
            }
            tested++;
            if (filter.mightContain(v)) {
                falsePositives++;
            }
        }
        double measured = (double) falsePositives / probes;

        assertEquals(target, measured, target * 0.25,
                "target " + target + ", measured " + measured);
        assertEquals(filter.expectedFalsePositiveRate(), measured, target * 0.25);
    }

    @Test
    void fillAndCountEstimateFollowTheory() {
        BloomFilter<Integer> filter = BloomFilter.create(Funnel.INTEGER, 200_000, 0.01);
        for (int i = 0; i < 150_000; i++) {
            filter.add(i);
        }
        double expectedFill = 1 - Math.exp(-(double) filter.numHashes() * 150_000 / filter.numBits());

        assertEquals(expectedFill, (double) filter.bitsSet() / filter.numBits(), 0.005);
        assertEquals(150_000, filter.approximateCount(), 150_000 * 0.02);
        assertFalse(filter.isOverCapacity());
    }

    @Test
    void hashPositionsAreDeterministicAndSpread() {
        byte[] bytes = Funnel.STRING.toBytes("hello");
        assertArrayEquals(HashPositions.positions(bytes, 1000, 5), HashPositions.positions(bytes, 1000, 5));

        // Chi-square-ish sanity check: 100k items into 100 buckets should be roughly uniform.
        int[] buckets = new int[100];
        for (int i = 0; i < 100_000; i++) {
            buckets[(int) HashPositions.positions(Funnel.INTEGER.toBytes(i), 100, 1)[0]]++;
        }
        for (int count : buckets) {
            assertTrue(count > 850 && count < 1150, "bucket count " + count);
        }
    }

    // ------------------------------------------------------------------ union

    @Test
    void unionContainsBothSides() {
        BloomFilter<String> monday = BloomFilter.create(Funnel.STRING, 10_000, 0.01);
        BloomFilter<String> tuesday = BloomFilter.create(Funnel.STRING, 10_000, 0.01);
        for (int i = 0; i < 3000; i++) {
            monday.add("mon-" + i);
            tuesday.add("tue-" + i);
        }
        long bitsBefore = monday.bitsSet();

        monday.unionWith(tuesday);

        for (int i = 0; i < 3000; i++) {
            assertTrue(monday.mightContain("mon-" + i));
            assertTrue(monday.mightContain("tue-" + i));
        }
        assertTrue(monday.bitsSet() > bitsBefore);
        assertEquals(6000, monday.approximateCount(), 6000 * 0.03);
    }

    @Test
    void unionRequiresSameShape() {
        BloomFilter<String> a = BloomFilter.create(Funnel.STRING, 1000, 0.01);
        BloomFilter<String> b = BloomFilter.create(Funnel.STRING, 1000, 0.001);

        assertFalse(a.isCompatible(b));
        assertThrows(IllegalArgumentException.class, () -> a.unionWith(b));
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void concurrentAddsLoseNothing() throws Exception {
        BloomFilter<Integer> filter = BloomFilter.create(Funnel.INTEGER, 400_000, 0.01);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            int base = t * 50_000;
            futures.add(pool.submit(() -> {
                for (int i = base; i < base + 50_000; i++) {
                    filter.add(i);
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        for (int i = 0; i < 400_000; i++) {
            assertTrue(filter.mightContain(i), "lost concurrent add of " + i);
        }
        // Rebuilding single-threaded must give exactly the same number of set bits.
        BloomFilter<Integer> reference = BloomFilter.create(Funnel.INTEGER, 400_000, 0.01);
        for (int i = 0; i < 400_000; i++) {
            reference.add(i);
        }
        assertEquals(reference.bitsSet(), filter.bitsSet());
    }

    // ------------------------------------------------------------------ counting filter

    @Test
    void countingFilterSupportsRemove() {
        CountingBloomFilter<String> filter = new CountingBloomFilter<>(Funnel.STRING, 10_000, 0.01);
        filter.add("a");
        filter.add("b");

        assertTrue(filter.remove("a"));
        assertFalse(filter.mightContain("a"));
        assertTrue(filter.mightContain("b"));
        assertEquals(1, filter.approximateCount());
    }

    @Test
    void countingFilterRefusesToRemoveAbsentItems() {
        CountingBloomFilter<String> filter = new CountingBloomFilter<>(Funnel.STRING, 10_000, 0.01);
        filter.add("a");

        assertFalse(filter.remove("never-added"));
        assertTrue(filter.mightContain("a"));
    }

    @Test
    void countingFilterDuplicatesNeedMatchingRemoves() {
        CountingBloomFilter<String> filter = new CountingBloomFilter<>(Funnel.STRING, 10_000, 0.01);
        filter.add("dup");
        filter.add("dup");

        filter.remove("dup");
        assertTrue(filter.mightContain("dup"));
        filter.remove("dup");
        assertFalse(filter.mightContain("dup"));
    }

    @Test
    void countingFilterNoFalseNegativesAfterRandomRemovals() {
        CountingBloomFilter<Integer> filter = new CountingBloomFilter<>(Funnel.INTEGER, 20_000, 0.01);
        Set<Integer> present = new HashSet<>();
        Random random = new Random(3);
        for (int op = 0; op < 60_000; op++) {
            int v = random.nextInt(30_000);
            if (random.nextBoolean() && present.add(v)) {
                filter.add(v);
            } else if (present.remove(v)) {
                assertTrue(filter.remove(v));
            }
        }
        for (int v : present) {
            assertTrue(filter.mightContain(v), "false negative for " + v);
        }
        assertEquals(present.size(), filter.approximateCount());
    }

    @Test
    void saturatedCountersStickToAvoidFalseNegatives() {
        // A tiny filter so every item hits the same few counters.
        CountingBloomFilter<Integer> filter = new CountingBloomFilter<>(Funnel.INTEGER, 1, 0.5);
        for (int i = 0; i < 40; i++) {
            filter.add(i);
        }
        for (long p = 0; p < filter.numCounters(); p++) {
            assertTrue(filter.counterAt(p) <= 15);
        }
        for (int i = 0; i < 39; i++) {
            filter.remove(i);
        }
        assertTrue(filter.mightContain(39), "item 39 must survive removal of all others");
    }

    // ------------------------------------------------------------------ scalable filter

    @Test
    void scalableFilterGrowsAndKeepsTheErrorBound() {
        ScalableBloomFilter<Integer> filter = new ScalableBloomFilter<>(Funnel.INTEGER, 1_000, 0.01);
        for (int i = 0; i < 200_000; i++) {
            filter.add(i);
        }
        for (int i = 0; i < 200_000; i++) {
            assertTrue(filter.mightContain(i));
        }

        int falsePositives = 0;
        for (int i = 10_000_000; i < 10_200_000; i++) {
            if (filter.mightContain(i)) {
                falsePositives++;
            }
        }
        assertTrue(filter.layers() >= 7, "layers: " + filter.layers());
        assertTrue(falsePositives / 200_000.0 < 0.012, "measured " + falsePositives / 200_000.0);
        assertTrue(filter.expectedFalsePositiveRate() <= 0.01 + 1e-9);
    }

    @Test
    void scalableFilterIgnoresRepeatedAdds() {
        ScalableBloomFilter<String> filter = new ScalableBloomFilter<>(Funnel.STRING, 10, 0.01);
        for (int i = 0; i < 1000; i++) {
            filter.add("same");
        }
        assertEquals(1, filter.layers());
        assertThrows(IllegalArgumentException.class,
                () -> new ScalableBloomFilter<>(Funnel.STRING, 10, 0.01, 1.0, 2));
    }
}
