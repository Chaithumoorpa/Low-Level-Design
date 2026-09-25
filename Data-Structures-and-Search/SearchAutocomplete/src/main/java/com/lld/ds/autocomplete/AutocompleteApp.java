package com.lld.ds.autocomplete;

import com.lld.ds.autocomplete.engine.AutocompleteEngine;
import com.lld.ds.autocomplete.engine.TypingSession;
import com.lld.ds.autocomplete.model.Suggestion;
import com.lld.ds.autocomplete.ranking.RankingStrategy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * Console demo with a small preloaded search history.
 *
 * <pre>
 *   java ... AutocompleteApp [k] [FREQUENCY|RECENCY]
 *
 *   type &lt;text&gt;     show suggestions after EVERY keystroke, like a real search box
 *   top &lt;prefix&gt;    suggestions for a prefix
 *   search &lt;query&gt;  record a search (it gains popularity)
 *   del &lt;query&gt;     remove a stored query
 *   block &lt;word&gt;    remove and ban every query containing the word
 *   q
 * </pre>
 */
public class AutocompleteApp {

    public static void main(String[] args) {
        int k = args.length > 0 ? Integer.parseInt(args[0]) : 3;
        RankingStrategy ranking = args.length > 1 && args[1].equalsIgnoreCase("RECENCY")
                ? RankingStrategy.BY_RECENCY : RankingStrategy.BY_FREQUENCY;
        AutocompleteEngine engine = new AutocompleteEngine(k, ranking);
        engine.loadAll(sampleHistory());

        System.out.println("Autocomplete (top " + k + ", " + ranking + ") with " + engine.termCount()
                + " stored queries. Commands: type | top | search | del | block | q");
        Scanner in = new Scanner(System.in);
        while (true) {
            System.out.print("> ");
            if (!in.hasNextLine()) {
                break;
            }
            String line = in.nextLine();
            String cmd = line.trim().split("\\s+", 2)[0].toLowerCase();
            String rest = line.trim().length() > cmd.length() ? line.trim().substring(cmd.length()).trim() : "";
            try {
                switch (cmd) {
                    case "q" -> {
                        return;
                    }
                    case "type" -> {
                        TypingSession session = engine.newSession();
                        for (char c : rest.toCharArray()) {
                            List<Suggestion> s = session.type(c);
                            System.out.printf("  %-18s -> %s%n", "\"" + session.text() + "\"", s);
                        }
                    }
                    case "top" -> System.out.println("  " + engine.suggest(rest));
                    case "search" -> System.out.println(engine.record(rest)
                            ? "  recorded; count is now " + engine.frequencyOf(rest)
                            : "  ignored (empty or blocked)");
                    case "del" -> System.out.println(engine.remove(rest) ? "  removed" : "  not found");
                    case "block" -> System.out.println("  blocked; removed " + engine.blockWord(rest) + " stored queries");
                    default -> System.out.println("  unknown command");
                }
            } catch (IllegalArgumentException e) {
                System.out.println("  ! " + e.getMessage());
            }
        }
    }

    private static Map<String, Long> sampleHistory() {
        Map<String, Long> history = new LinkedHashMap<>();
        history.put("java", 50L);
        history.put("javascript", 80L);
        history.put("java streams", 30L);
        history.put("java interview questions", 45L);
        history.put("javascript array methods", 25L);
        history.put("jakarta ee", 10L);
        history.put("jar file", 12L);
        history.put("python", 90L);
        history.put("python list comprehension", 40L);
        history.put("pycharm", 20L);
        history.put("system design", 70L);
        history.put("system design interview", 65L);
        history.put("lru cache", 35L);
        history.put("lfu cache", 15L);
        return history;
    }
}
