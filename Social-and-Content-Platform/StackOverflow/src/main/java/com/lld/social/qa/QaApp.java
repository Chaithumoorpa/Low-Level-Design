package com.lld.social.qa;

import com.lld.social.qa.model.Answer;
import com.lld.social.qa.model.QaException;
import com.lld.social.qa.model.Question;
import com.lld.social.qa.model.VoteType;
import com.lld.social.qa.service.ManualClock;
import com.lld.social.qa.service.QaService;
import com.lld.social.qa.service.ReputationPolicy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** A week on a small Q&A site, on a simulated clock. */
public class QaApp {

    public static void main(String[] args) {
        ManualClock clock = new ManualClock(Instant.parse("2027-06-01T09:00:00Z"));
        QaService site = new QaService(new ReputationPolicy(), clock);
        site.register("nia", "Nia");           // new user asking
        site.register("omar", "Omar");         // experienced answerer
        site.register("pia", "Pia");           // another answerer
        site.register("mod1", "Mods-1");
        site.register("mod2", "Mods-2");
        site.register("mod3", "Mods-3");
        site.importReputation("omar", 3000, "association bonus");
        site.importReputation("pia", 200, "association bonus");
        for (String m : List.of("mod1", "mod2", "mod3")) {
            site.importReputation(m, 5000, "association bonus");
        }

        step("Nia asks; newcomers can ask and answer but not vote yet");
        Question q = site.ask("nia", "Why does HashMap iteration order change?",
                "I put the same keys in and get a different order after adding more entries.", "java", "hashmap");
        System.out.println("   " + q);

        step("Answers, votes and an accepted answer");
        Answer weak = site.answer("pia", q.id(), "Use a TreeMap.");
        Answer good = site.answer("omar", q.id(), "HashMap order depends on hash buckets; resizing rehashes. Use LinkedHashMap for insertion order.");
        attempt(() -> site.vote("nia", good.id(), VoteType.UP));
        site.vote("omar", q.id(), VoteType.UP);
        site.vote("pia", good.id(), VoteType.UP);
        site.vote("mod1", good.id(), VoteType.UP);
        site.vote("omar", weak.id(), VoteType.DOWN);
        site.accept("nia", good.id());
        attempt(() -> site.vote("omar", good.id(), VoteType.UP));
        System.out.println("   answers as shown: " + site.answersOf(q.id()));
        System.out.println("   Nia " + site.user("nia").reputation() + ", Omar " + site.user("omar").reputation()
                + ", Pia " + site.user("pia").reputation());

        step("Changing your mind reverses the reputation exactly");
        long before = site.user("pia").reputation();
        site.vote("omar", weak.id(), VoteType.DOWN);         // same vote again = undo
        System.out.println("   Omar removes his downvote: Pia " + before + " -> " + site.user("pia").reputation());
        site.vote("mod2", weak.id(), VoteType.UP);
        site.vote("mod2", weak.id(), VoteType.DOWN);         // switch
        System.out.println("   Mods-2 switches up -> down: Pia now " + site.user("pia").reputation() + ", score " + weak.score());

        step("Comments need 50 reputation outside your own threads");
        attempt(() -> "Nia comments under an answer to her own question: " + site.comment("nia", good.id(), "Thanks, that fixed it!").id());
        Question other = site.ask("pia", "How do I read a file line by line?", "Looking for the idiomatic way.", "java", "io");
        attempt(() -> site.comment("nia", other.id(), "Try Files.lines"));

        step("A duplicate question gets closed by three trusted users");
        Question dup = site.ask("nia", "HashMap order keeps changing, why?", "Same as before really.", "java");
        for (String m : List.of("mod1", "mod2", "mod3")) {
            boolean closed = site.voteToClose(m, dup.id(), "duplicate of " + q.id());
            System.out.println("   " + m + " votes to close -> " + (closed ? "CLOSED" : dup.closeVotes() + "/3"));
        }
        attempt(() -> site.answer("omar", dup.id(), "See the original."));

        step("Bounty: Pia pays 50 reputation to get a better answer to her question");
        site.importReputation("pia", 100, "association bonus");
        long piaBefore = site.user("pia").reputation();
        site.offerBounty("pia", other.id(), 50);
        Answer lines = site.answer("omar", other.id(), "Use Files.lines(path) in a try-with-resources block.");
        clock.advance(Duration.ofDays(2));
        site.awardBounty("pia", lines.id());
        System.out.println("   Pia " + piaBefore + " -> " + site.user("pia").reputation() + ", Omar got +50");

        step("Edits keep history; only authors or 2000+ rep may edit");
        attempt(() -> {
            site.edit("pia", good.id(), null, "hacked", "vandalism");
            return "edited";
        });
        site.edit("omar", q.id(), "Why does HashMap iteration order change after adding entries?", q.body(), "clarified title");
        q.revisions().forEach(r -> System.out.println("   rev " + r.number() + " by " + r.editorId() + ": " + r.title() + " (" + r.summary() + ")"));

        step("Search");
        System.out.println("   'hashmap order' by votes: " + site.search("hashmap order", Set.of(), false, QaService.Sort.VOTES));
        System.out.println("   tag io, unanswered only: " + site.search("", Set.of("io"), true, QaService.Sort.NEWEST));
        System.out.println("   (Omar's bounty answer has no upvotes and isn't accepted, so it still counts as unanswered)");

        step("Omar's reputation history");
        site.reputationHistory("omar").forEach(e -> System.out.println("   " + e));
    }

    private static void step(String title) {
        System.out.println("\n> " + title);
    }

    private static void attempt(java.util.function.Supplier<Object> action) {
        try {
            System.out.println("   " + action.get());
        } catch (QaException e) {
            System.out.println("   [refused] " + e.getMessage());
        }
    }
}
