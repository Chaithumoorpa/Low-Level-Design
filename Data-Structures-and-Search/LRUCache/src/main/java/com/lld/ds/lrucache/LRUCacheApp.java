package com.lld.ds.lrucache;

import com.lld.ds.lrucache.lru.LRUCache;

import java.util.Scanner;

/**
 * Interactive console to watch the LRU order change.
 *
 * <pre>
 *   java ... LRUCacheApp [capacity]       (default 3)
 *
 *   put k v    insert / update
 *   get k      read (moves k to the front)
 *   del k      remove
 *   stats      hits / misses / evictions
 *   q          quit
 * </pre>
 */
public class LRUCacheApp {

    public static void main(String[] args) {
        int capacity = args.length > 0 ? Integer.parseInt(args[0]) : 3;
        LRUCache<String, String> cache = new LRUCache<>(capacity,
                (key, value) -> System.out.println("  evicted " + key + "=" + value + " (least recently used)"));

        Scanner in = new Scanner(System.in);
        System.out.println("LRU cache, capacity " + capacity + ". Commands: put k v | get k | del k | stats | q");

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
                    case "stats" -> System.out.println("  " + cache.stats());
                    default -> System.out.println("  unknown command");
                }
            } catch (IllegalArgumentException e) {
                System.out.println("  ! " + e.getMessage());
            }
            System.out.println("  " + cache);
        }
    }

    private static String arg(String[] parts, int index) {
        if (index >= parts.length) {
            throw new IllegalArgumentException("missing argument");
        }
        return parts[index];
    }
}
