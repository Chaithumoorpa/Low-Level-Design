package com.lld.social.network;

import com.lld.social.network.feed.FeedRanking;
import com.lld.social.network.model.FriendRequest;
import com.lld.social.network.model.Post;
import com.lld.social.network.model.SocialException;
import com.lld.social.network.model.Visibility;
import com.lld.social.network.service.ManualClock;
import com.lld.social.network.service.SocialNetwork;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocialNetworkTest {

    private ManualClock clock;
    private SocialNetwork net;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(Instant.parse("2027-07-01T08:00:00Z"));
        net = new SocialNetwork(clock);
        for (String u : List.of("a", "b", "c", "d", "e", "f")) {
            net.register(u, u.toUpperCase());
        }
    }

    private void befriend(String x, String y) {
        net.accept(y, net.sendFriendRequest(x, y).id());
    }

    private static List<String> ids(List<Post> posts) {
        return posts.stream().map(Post::id).toList();
    }

    // ------------------------------------------------------------------ friendship

    @Nested
    class Friendship {

        @Test
        void requestLifecycle() {
            FriendRequest r = net.sendFriendRequest("a", "b");
            assertEquals(List.of(r), net.pendingRequestsFor("b"));
            assertThrows(SocialException.class, () -> net.sendFriendRequest("a", "b"), "duplicate");
            assertThrows(SocialException.class, () -> net.accept("a", r.id()), "only the receiver");
            net.accept("b", r.id());
            assertTrue(net.areFriends("a", "b") && net.areFriends("b", "a"));
            assertThrows(SocialException.class, () -> net.sendFriendRequest("b", "a"), "already friends");
            assertThrows(SocialException.class, () -> net.sendFriendRequest("a", "a"));
        }

        @Test
        void crossedRequestsBecomeFriendship() {
            FriendRequest r = net.sendFriendRequest("a", "b");
            net.sendFriendRequest("b", "a");
            assertEquals(FriendRequest.Status.ACCEPTED, r.status());
            assertTrue(net.areFriends("a", "b"));
        }

        @Test
        void declineCancelUnfriend() {
            FriendRequest r = net.sendFriendRequest("a", "b");
            net.decline("b", r.id());
            assertEquals(FriendRequest.Status.DECLINED, r.status());
            FriendRequest again = net.sendFriendRequest("a", "b");      // allowed after a decline
            net.cancelRequest("a", again.id());
            assertThrows(SocialException.class, () -> net.accept("b", again.id()));
            befriend("a", "c");
            net.unfriend("c", "a");
            assertFalse(net.areFriends("a", "c"));
            assertThrows(SocialException.class, () -> net.unfriend("a", "c"));
        }
    }

    // ------------------------------------------------------------------ privacy

    @Nested
    class Privacy {

        @Test
        void visibilityRules() {
            befriend("a", "b");
            Post pub = net.post("a", "hello world", Visibility.PUBLIC);
            Post fr = net.post("a", "friends only", Visibility.FRIENDS);
            Post me = net.post("a", "diary", Visibility.ONLY_ME);
            assertEquals(List.of(me, fr, pub), net.timeline("a", "a"));
            assertEquals(List.of(fr, pub), net.timeline("b", "a"));
            assertEquals(List.of(pub), net.timeline("c", "a"));
            assertThrows(SocialException.class, () -> net.like("c", fr.id()));
            assertThrows(SocialException.class, () -> net.comment("b", me.id(), "x"));
            assertThrows(SocialException.class, () -> net.post("a", " ", Visibility.PUBLIC));
        }

        @Test
        void blockingCutsEverythingBothWays() {
            befriend("a", "b");
            net.follow("b", "a");
            net.sendFriendRequest("c", "a");
            Post pub = net.post("a", "public", Visibility.PUBLIC);
            net.block("a", "b");
            assertFalse(net.areFriends("a", "b"));
            assertEquals(List.of(), net.timeline("b", "a"), "even public posts");
            assertEquals(List.of(), net.timeline("a", "b"), "both ways");
            assertThrows(SocialException.class, () -> net.like("b", pub.id()));
            assertThrows(SocialException.class, () -> net.sendFriendRequest("b", "a"));
            assertThrows(SocialException.class, () -> net.follow("b", "a"));
            assertEquals(List.of(), net.searchUsers("b", "A"));
            net.block("a", "c");
            assertEquals(List.of(), net.pendingRequestsFor("a"), "pending request cancelled");
            net.unblock("a", "b");
            assertEquals(List.of(pub), net.timeline("b", "a"), "unblocking does not restore friendship");
            assertFalse(net.areFriends("a", "b"));
        }

        @Test
        void onlyTheAuthorDeletes() {
            befriend("a", "b");
            Post p = net.post("a", "x", Visibility.FRIENDS);
            assertThrows(SocialException.class, () -> net.deletePost("b", p.id()));
            net.deletePost("a", p.id());
            assertEquals(List.of(), net.timeline("a", "a"));
        }
    }

    // ------------------------------------------------------------------ feed

    @Nested
    class Feed {

        @Test
        void feedMixesOwnFriendsAndFollowedPublicPosts() {
            befriend("a", "b");
            net.follow("a", "c");
            Post own = net.post("a", "mine", Visibility.ONLY_ME);
            Post friend = net.post("b", "friend", Visibility.FRIENDS);
            Post followedPublic = net.post("c", "public", Visibility.PUBLIC);
            net.post("c", "for c's friends", Visibility.FRIENDS);
            net.post("d", "stranger", Visibility.PUBLIC);
            assertEquals(List.of(followedPublic, friend, own), net.feed("a", FeedRanking.chronological(), 0, 10));
        }

        @Test
        void paging() {
            List<Post> made = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                made.add(net.post("a", "p" + i, Visibility.PUBLIC));
            }
            assertEquals(ids(List.of(made.get(6), made.get(5), made.get(4))), ids(net.feed("a", FeedRanking.chronological(), 0, 3)));
            assertEquals(ids(List.of(made.get(0))), ids(net.feed("a", FeedRanking.chronological(), 2, 3)));
            assertEquals(List.of(), net.feed("a", FeedRanking.chronological(), 3, 3));
        }

        @Test
        void engagementBeatsRecencyButDecays() {
            befriend("a", "b");
            befriend("a", "c");
            Post popular = net.post("b", "popular", Visibility.PUBLIC);    // public, so c (not b's friend) can react
            for (String u : List.of("a", "c", "d")) {
                net.like(u, popular.id());
            }
            net.comment("c", popular.id(), "wow");
            clock.advance(Duration.ofHours(1));
            Post fresh = net.post("c", "fresh", Visibility.FRIENDS);
            assertEquals(List.of(popular, fresh), net.feed("a", FeedRanking.engagement(), 0, 10));
            clock.advance(Duration.ofDays(3));
            Post newer = net.post("c", "newer", Visibility.FRIENDS);
            assertEquals(newer, net.feed("a", FeedRanking.engagement(), 0, 10).get(0), "old engagement fades");
        }

        @Test
        void likesToggleAndNotifyOnlyOthers() {
            befriend("a", "b");
            Post p = net.post("a", "x", Visibility.FRIENDS);
            assertTrue(net.like("b", p.id()));
            assertFalse(net.like("b", p.id()));
            net.like("a", p.id());
            net.comment("a", p.id(), "self");
            assertEquals(1, p.likeCount());
            assertEquals(List.of("A sent you a friend request", "B liked your post " + p.id()).get(1),
                    net.notifications("a").get(1));
            assertEquals(2, net.notifications("a").size(), "accepted + one like; own actions don't notify");
        }
    }

    // ------------------------------------------------------------------ graph

    @Nested
    class Graph {

        @Test
        void mutualsSuggestionsAndDegrees() {
            befriend("a", "b");
            befriend("a", "c");
            befriend("b", "d");
            befriend("c", "d");
            befriend("c", "e");
            befriend("e", "f");
            assertEquals(List.of("a", "d"), net.mutualFriends("b", "c"));
            assertEquals(List.of("d", "e"), net.suggestions("a", 5), "d has 2 mutual friends, e has 1");
            assertEquals(3, net.degreesOfSeparation("a", "f"));
            assertEquals(0, net.degreesOfSeparation("a", "a"));
            net.block("a", "d");
            assertEquals(List.of("e"), net.suggestions("a", 5), "blocked users are never suggested");
            net.sendFriendRequest("a", "e");
            assertEquals(List.of(), net.suggestions("a", 5), "pending requests aren't suggested again");
        }

        @Test
        void unreachable() {
            assertEquals(-1, net.degreesOfSeparation("a", "f"));
        }

        @Test
        void bfsMatchesBruteForceOnRandomGraphs() {
            Random r = new Random(9);
            List<String> names = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                net.register("u" + i, "U" + i);
                names.add("u" + i);
            }
            int[][] dist = new int[25][25];
            for (int[] row : dist) {
                java.util.Arrays.fill(row, 1_000);
            }
            for (int i = 0; i < 25; i++) {
                dist[i][i] = 0;
            }
            for (int k = 0; k < 40; k++) {
                int x = r.nextInt(25);
                int y = r.nextInt(25);
                if (x != y && !net.areFriends(names.get(x), names.get(y))) {
                    befriend(names.get(x), names.get(y));
                    dist[x][y] = 1;
                    dist[y][x] = 1;
                }
            }
            for (int k = 0; k < 25; k++) {                       // Floyd–Warshall oracle
                for (int i = 0; i < 25; i++) {
                    for (int j = 0; j < 25; j++) {
                        dist[i][j] = Math.min(dist[i][j], dist[i][k] + dist[k][j]);
                    }
                }
            }
            for (int i = 0; i < 25; i++) {
                for (int j = 0; j < 25; j++) {
                    int expected = dist[i][j] >= 1_000 ? -1 : dist[i][j];
                    assertEquals(expected, net.degreesOfSeparation(names.get(i), names.get(j)));
                }
            }
        }
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void concurrentLikesAndFeedReads() throws Exception {
        List<String> fans = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            net.register("fan" + i, "Fan" + i);
            fans.add("fan" + i);
        }
        Post p = net.post("a", "viral", Visibility.PUBLIC);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (String f : fans) {
            futures.add(pool.submit(() -> {
                start.await();
                net.follow(f, "a");
                net.like(f, p.id());
                return net.feed(f, FeedRanking.engagement(), 0, 5);
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(60, p.likeCount());
        assertEquals(Set.copyOf(fans), p.likes());
        assertEquals(60, net.notifications("a").size());
    }
}
