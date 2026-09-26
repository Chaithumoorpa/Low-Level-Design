package com.lld.social.network;

import com.lld.social.network.feed.FeedRanking;
import com.lld.social.network.model.FriendRequest;
import com.lld.social.network.model.Post;
import com.lld.social.network.model.SocialException;
import com.lld.social.network.model.Visibility;
import com.lld.social.network.service.ManualClock;
import com.lld.social.network.service.SocialNetwork;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/** A handful of people on a small social network, on a simulated clock. */
public class SocialNetworkApp {

    public static void main(String[] args) {
        ManualClock clock = new ManualClock(Instant.parse("2027-07-01T08:00:00Z"));
        SocialNetwork net = new SocialNetwork(clock);
        for (String[] u : new String[][]{{"ava", "Ava"}, {"ben", "Ben"}, {"cho", "Cho"}, {"dev", "Dev"},
                {"eli", "Eli"}, {"fin", "Fin"}, {"chef", "Chef Gio"}}) {
            net.register(u[0], u[1]);
        }

        step("Friendships: requests, a crossed request, a decline");
        FriendRequest r1 = net.sendFriendRequest("ava", "ben");
        net.accept("ben", r1.id());
        net.sendFriendRequest("ben", "cho");
        net.sendFriendRequest("cho", "ben");                      // crossed: accepts Ben's request
        net.accept("dev", net.sendFriendRequest("cho", "dev").id());
        net.accept("eli", net.sendFriendRequest("dev", "eli").id());
        net.decline("fin", net.sendFriendRequest("eli", "fin").id());
        net.accept("cho", net.sendFriendRequest("ava", "cho").id());
        attempt(() -> net.sendFriendRequest("ava", "ben"));
        System.out.println("   Ava's friends " + net.friendsOf("ava") + ", Ben's " + net.friendsOf("ben"));

        step("Graph queries");
        System.out.println("   mutual friends of Ava and Ben: " + net.mutualFriends("ava", "ben"));
        System.out.println("   people Ava may know: " + net.suggestions("ava", 3));
        System.out.println("   Ava -> Eli: " + net.degreesOfSeparation("ava", "eli") + " steps; Ava -> Fin: "
                + net.degreesOfSeparation("ava", "fin"));

        step("Posts with different audiences; Ava follows a public chef");
        net.follow("ava", "chef");
        Post recipe = net.post("chef", "Tonight: 20-minute risotto", Visibility.PUBLIC);
        clock.advance(Duration.ofHours(1));
        Post party = net.post("ben", "Party at mine on Saturday!", Visibility.FRIENDS);
        clock.advance(Duration.ofHours(1));
        Post diary = net.post("ben", "Note to self: buy ice", Visibility.ONLY_ME);
        clock.advance(Duration.ofHours(1));
        Post hike = net.post("cho", "Sunrise hike photos", Visibility.FRIENDS);
        System.out.println("   Dev sees Ben's timeline: " + net.timeline("dev", "ben"));
        System.out.println("   Ava sees Ben's timeline: " + net.timeline("ava", "ben"));
        attempt(() -> net.like("dev", party.id()));

        step("Engagement");
        for (String u : List.of("ava", "cho", "ben")) {
            net.like(u, recipe.id());
        }
        net.comment("ava", recipe.id(), "Trying this tonight!");
        net.like("ava", party.id());
        net.comment("cho", party.id(), "I'll bring snacks");

        step("Ava's feed");
        System.out.println("   newest first:");
        net.feed("ava", FeedRanking.chronological(), 0, 10).forEach(p -> System.out.println("     " + p));
        System.out.println("   top posts (engagement decaying with age):");
        net.feed("ava", FeedRanking.engagement(), 0, 10).forEach(p -> System.out.printf("     %.3f %s%n",
                FeedRanking.score(p, clock.instant()), p));

        step("Cho blocks Ava");
        net.block("cho", "ava");
        System.out.println("   still friends? " + net.areFriends("ava", "cho") + "; Ava's feed now: "
                + net.feed("ava", FeedRanking.chronological(), 0, 10).stream().map(Post::id).toList());
        attempt(() -> net.comment("ava", hike.id(), "nice!"));
        System.out.println("   Ava searching 'ch': " + net.searchUsers("ava", "ch"));
        attempt(() -> net.sendFriendRequest("ava", "cho"));

        step("Notifications");
        for (String u : List.of("ben", "chef")) {
            System.out.println("   " + u + ": " + net.notifications(u));
        }
        System.out.println("   (Ben's private diary " + diary.id() + " was never visible to anyone else)");
    }

    private static void step(String title) {
        System.out.println("\n> " + title);
    }

    private static void attempt(Supplier<Object> action) {
        try {
            System.out.println("   " + action.get());
        } catch (SocialException e) {
            System.out.println("   [refused] " + e.getMessage());
        }
    }
}
