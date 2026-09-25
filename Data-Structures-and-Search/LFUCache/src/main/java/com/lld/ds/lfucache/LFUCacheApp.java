package com.lld.ds.lfucache;

import com.lld.ds.lfucache.lfu.LFUCache;

import java.util.Scanner;

/**
 * Interactive console that shows the frequency buckets after every command.
 *
 * <pre>
 *   java ... LFUCacheApp [capacity] [agingEvery]     (defaults 3 and 0 = no aging)
 *
 *   put k v    insert / update (an update counts as a use)
 *   get k      read (+1 frequency)
 *   del k      remove
 *   age        halve all frequencies now
 *   stats      hits / misses / evictions
 *   q          quit
 * </pre>
 */
public class LFUCacheApp {

    public static void main(String[] args) {
        int capacity = args.length > 0 ? Integer.parseInt(args[0]) : 3;
        int aging = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        LFUCache<String, String> cache = LFUCache.<String, String>builder()
                .capacity(capacity)
                .agingEvery(aging)
                .evictionListener((k, v) -> System.out.println("  evicted " + k + "=" + v))
                .build();

        Scanner in = new Scanner(System.in);
        System.out.println("LFU cache, capacity " + capacity + (aging > 0 ? ", aging every " + aging + " ops" : "")
                + ". Commands: put k v | get k | del k | age | stats | q");
        System.out.println("Buckets are shown as f=<frequency> [most recent ... least recent]");

        while (true) {
            System.out.print("> ");
            if (!in.hasNextLine()) {
                break;
            }
            String[] p = in.nextLine().trim().split("\\s+");
            try {
                switch (p[0].toLowerCase()) {
                    case "q" -> {
                        return;
                    }
                    case "put" -> cache.put(arg(p, 1), arg(p, 2));
                    case "get" -> System.out.println("  " + cache.get(arg(p, 1)).orElse("(miss)"));
                    case "del" -> System.out.println(cache.remove(arg(p, 1)) ? "  removed" : "  not found");
                    case "age" -> cache.halveFrequencies();
                    case "stats" -> System.out.println("  " + cache.stats());
                    default -> System.out.println("  unknown command");
                }
            } catch (IllegalArgumentException e) {
                System.out.println("  ! " + e.getMessage());
            }
            System.out.println("  " + cache + cache.nextVictim().map(v -> "   (next victim: " + v + ")").orElse(""));
        }
    }

    private static String arg(String[] parts, int index) {
        if (index >= parts.length) {
            throw new IllegalArgumentException("missing argument");
        }
        return parts[index];
    }
}
