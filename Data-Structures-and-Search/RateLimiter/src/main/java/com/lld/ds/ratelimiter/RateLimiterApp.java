package com.lld.ds.ratelimiter;

import com.lld.ds.ratelimiter.algorithm.Algorithm;
import com.lld.ds.ratelimiter.core.Decision;
import com.lld.ds.ratelimiter.core.ManualTimeSource;
import com.lld.ds.ratelimiter.core.RateLimitConfig;
import com.lld.ds.ratelimiter.core.RateLimiter;

/**
 * Runs the same simulated traffic through all five algorithms (limit 5 per second) on a manual
 * clock, so the differences are visible instantly and deterministically.
 */
public class RateLimiterApp {

    private static final RateLimitConfig CONFIG = RateLimitConfig.perSecond(5);

    public static void main(String[] args) {
        System.out.println("Limit: " + CONFIG + "   (A = allowed, . = denied)");

        scenario("1) Burst: 10 requests at t=0 ms, then 10 more at t=500 ms",
                new long[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 500, 500, 500, 500, 500, 500, 500, 500, 500, 500});

        scenario("2) Window boundary: 10 requests at t=900..990 ms, 10 at t=1000..1090 ms",
                timeline(900, 10, 10, 1000, 10, 10));

        scenario("3) Steady: one request every 150 ms for 3 s",
                timeline(0, 20, 150));

        System.out.println();
        System.out.println("Leaky bucket delays (traffic shaping), 7 requests at t=0:");
        ManualTimeSource clock = new ManualTimeSource();
        RateLimiter leaky = Algorithm.LEAKY_BUCKET.create(CONFIG, clock);
        for (int i = 0; i < 7; i++) {
            System.out.println("  request " + (i + 1) + ": " + leaky.tryAcquire("client"));
        }
    }

    private static void scenario(String title, long[] times) {
        System.out.println();
        System.out.println(title);
        for (Algorithm algorithm : Algorithm.values()) {
            ManualTimeSource clock = new ManualTimeSource();
            RateLimiter limiter = algorithm.create(CONFIG, clock);
            StringBuilder marks = new StringBuilder();
            int allowed = 0;
            for (long t : times) {
                clock.set(t);
                Decision d = limiter.tryAcquire("client");
                marks.append(d.allowed() ? 'A' : '.');
                if (d.allowed()) {
                    allowed++;
                }
            }
            System.out.printf("  %-23s %s  allowed %2d/%d%n", algorithm, marks, allowed, times.length);
        }
    }

    /** Requests at start, start+step, ... (count of them); pairs of (start, count, step) can be chained. */
    private static long[] timeline(long... spec) {
        int total = 0;
        for (int i = 1; i < spec.length; i += 3) {
            total += (int) spec[i];
        }
        long[] times = new long[total];
        int idx = 0;
        for (int i = 0; i < spec.length; i += 3) {
            for (int j = 0; j < spec[i + 1]; j++) {
                times[idx++] = spec[i] + j * spec[i + 2];
            }
        }
        return times;
    }
}
