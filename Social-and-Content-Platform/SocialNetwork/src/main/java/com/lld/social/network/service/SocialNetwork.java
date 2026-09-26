package com.lld.social.network.service;

import com.lld.social.network.feed.FeedRanking;
import com.lld.social.network.model.Comment;
import com.lld.social.network.model.FriendRequest;
import com.lld.social.network.model.Post;
import com.lld.social.network.model.SocialException;
import com.lld.social.network.model.User;
import com.lld.social.network.model.Visibility;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Facade for relationships, posts, feed and notifications.
 *
 * <p>Two relationship kinds: <b>friendship</b> (mutual, needs a request and acceptance) and
 * <b>follow</b> (one-way, no approval; followers see public posts). <b>Blocking</b> overrides both: it
 * removes the friendship, follows and pending requests, and hides each side's content from the other.
 *
 * <p>Every read of a post goes through {@link #canSee}, so privacy is enforced in exactly one place.
 * Concurrency: a read-write lock; feeds and suggestions (reads) run in parallel, changes are exclusive.
 */
public final class SocialNetwork {

    private final ReadWriteLock rw = new ReentrantReadWriteLock();
    private final Map<String, User> users = new LinkedHashMap<>();
    private final Map<String, Set<String>> friends = new HashMap<>();
    private final Map<String, Set<String>> following = new HashMap<>();
    private final Map<String, Set<String>> blocked = new HashMap<>();           // who each user has blocked
    private final Map<String, FriendRequest> requests = new LinkedHashMap<>();
    private final Map<String, Post> posts = new LinkedHashMap<>();
    private final Map<String, List<Post>> postsByAuthor = new HashMap<>();
    private final Map<String, List<String>> inbox = new HashMap<>();
    private final List<NotificationListener> listeners = new CopyOnWriteArrayList<>();
    private final Clock clock;
    private long requestSeq;
    private long postSeq;

    public SocialNetwork(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public void addListener(NotificationListener l) {
        listeners.add(l);
    }

    // ------------------------------------------------------------------ users

    public User register(String id, String name) {
        return write(() -> {
            if (users.containsKey(id)) {
                throw new SocialException("User " + id + " exists");
            }
            User u = new User(id, name);
            users.put(id, u);
            friends.put(id, new TreeSet<>());
            following.put(id, new TreeSet<>());
            blocked.put(id, new HashSet<>());
            postsByAuthor.put(id, new ArrayList<>());
            return u;
        });
    }

    /** Name search that never reveals people who blocked the searcher (or whom they blocked). */
    public List<User> searchUsers(String viewerId, String query) {
        String q = query.toLowerCase(Locale.ROOT);
        return read(() -> users.values().stream()
                .filter(u -> !u.id().equals(viewerId) && !isBlockedEitherWay(viewerId, u.id()))
                .filter(u -> u.name().toLowerCase(Locale.ROOT).contains(q))
                .toList());
    }

    // ------------------------------------------------------------------ friendship

    /** If the other person already asked you, sending a request back simply accepts theirs. */
    public FriendRequest sendFriendRequest(String fromId, String toId) {
        return write(() -> {
            user(fromId);
            user(toId);
            if (fromId.equals(toId)) {
                throw new SocialException("You can't befriend yourself");
            }
            if (isBlockedEitherWay(fromId, toId)) {
                throw new SocialException("Can't send a request to " + name(toId));
            }
            if (areFriends(fromId, toId)) {
                throw new SocialException("Already friends");
            }
            FriendRequest reverse = pending(toId, fromId);
            if (reverse != null) {
                acceptLocked(reverse);
                return reverse;
            }
            if (pending(fromId, toId) != null) {
                throw new SocialException("Request already pending");
            }
            FriendRequest r = new FriendRequest("R" + (++requestSeq), fromId, toId, now());
            requests.put(r.id(), r);
            notifyLocked(toId, name(fromId) + " sent you a friend request");
            return r;
        });
    }

    public void accept(String userId, String requestId) {
        write(() -> {
            FriendRequest r = requestFor(userId, requestId);
            acceptLocked(r);
            return null;
        });
    }

    public void decline(String userId, String requestId) {
        write(() -> {
            requestFor(userId, requestId).setStatus(FriendRequest.Status.DECLINED);
            return null;
        });
    }

    public void cancelRequest(String userId, String requestId) {
        write(() -> {
            FriendRequest r = requests.get(requestId);
            if (r == null || !r.fromId().equals(userId) || r.status() != FriendRequest.Status.PENDING) {
                throw new SocialException("No pending request " + requestId + " from " + userId);
            }
            r.setStatus(FriendRequest.Status.CANCELLED);
            return null;
        });
    }

    public void unfriend(String a, String b) {
        write(() -> {
            if (!friends.get(a).remove(b) | !friends.get(b).remove(a)) {
                throw new SocialException(name(a) + " and " + name(b) + " are not friends");
            }
            return null;
        });
    }

    public List<FriendRequest> pendingRequestsFor(String userId) {
        return read(() -> requests.values().stream()
                .filter(r -> r.toId().equals(userId) && r.status() == FriendRequest.Status.PENDING).toList());
    }

    // ------------------------------------------------------------------ follow & block

    public void follow(String followerId, String targetId) {
        write(() -> {
            user(targetId);
            if (followerId.equals(targetId) || isBlockedEitherWay(followerId, targetId)) {
                throw new SocialException("Can't follow " + name(targetId));
            }
            following.get(followerId).add(targetId);
            return null;
        });
    }

    public void unfollow(String followerId, String targetId) {
        write(() -> following.get(followerId).remove(targetId));
    }

    /** Cuts every tie: friendship, follows (both ways), pending requests. */
    public void block(String userId, String targetId) {
        write(() -> {
            user(targetId);
            blocked.get(userId).add(targetId);
            friends.get(userId).remove(targetId);
            friends.get(targetId).remove(userId);
            following.get(userId).remove(targetId);
            following.get(targetId).remove(userId);
            requests.values().stream()
                    .filter(r -> r.status() == FriendRequest.Status.PENDING)
                    .filter(r -> Set.of(r.fromId(), r.toId()).equals(Set.of(userId, targetId)))
                    .forEach(r -> r.setStatus(FriendRequest.Status.CANCELLED));
            return null;
        });
    }

    public void unblock(String userId, String targetId) {
        write(() -> blocked.get(userId).remove(targetId));
    }

    // ------------------------------------------------------------------ posts

    public Post post(String authorId, String text, Visibility visibility) {
        return write(() -> {
            user(authorId);
            if (text == null || text.isBlank()) {
                throw new SocialException("Empty post");
            }
            long seq = ++postSeq;
            Post p = new Post("P" + seq, seq, authorId, text.strip(), visibility, now());
            posts.put(p.id(), p);
            postsByAuthor.get(authorId).add(p);
            return p;
        });
    }

    public void deletePost(String userId, String postId) {
        write(() -> {
            Post p = postVisibleTo(userId, postId);
            if (!p.authorId().equals(userId)) {
                throw new SocialException("Only the author can delete " + postId);
            }
            posts.remove(postId);
            postsByAuthor.get(userId).remove(p);
            return null;
        });
    }

    public boolean like(String userId, String postId) {
        return write(() -> {
            Post p = postVisibleTo(userId, postId);
            boolean liked = p.toggleLike(userId);
            if (liked && !p.authorId().equals(userId)) {
                notifyLocked(p.authorId(), name(userId) + " liked your post " + p.id());
            }
            return liked;
        });
    }

    public Comment comment(String userId, String postId, String text) {
        return write(() -> {
            Post p = postVisibleTo(userId, postId);
            Comment c = new Comment(userId, text, now());
            p.addComment(c);
            if (!p.authorId().equals(userId)) {
                notifyLocked(p.authorId(), name(userId) + " commented on " + p.id() + ": " + text);
            }
            return c;
        });
    }

    /** The single privacy rule. */
    public boolean canSee(String viewerId, Post p) {
        return read(() -> {
            if (p.authorId().equals(viewerId)) {
                return true;
            }
            if (isBlockedEitherWay(viewerId, p.authorId())) {
                return false;
            }
            return switch (p.visibility()) {
                case PUBLIC -> true;
                case FRIENDS -> areFriends(viewerId, p.authorId());
                case ONLY_ME -> false;
            };
        });
    }

    /** A profile page: the owner's posts that the viewer may see, newest first. */
    public List<Post> timeline(String viewerId, String ownerId) {
        return read(() -> {
            user(ownerId);
            List<Post> out = new ArrayList<>(postsByAuthor.get(ownerId).stream().filter(p -> canSee(viewerId, p)).toList());
            out.sort(Comparator.comparingLong(Post::sequence).reversed());
            return out;
        });
    }

    /**
     * News feed (fan-out on read): own posts, friends' posts and followed users' posts, filtered by
     * {@link #canSee}, ordered by the ranking, one page at a time.
     */
    public List<Post> feed(String viewerId, FeedRanking ranking, int page, int pageSize) {
        return read(() -> {
            user(viewerId);
            Set<String> sources = new TreeSet<>(friends.get(viewerId));
            sources.addAll(following.get(viewerId));
            sources.add(viewerId);
            List<Post> candidates = new ArrayList<>();
            for (String author : sources) {
                for (Post p : postsByAuthor.get(author)) {
                    if (canSee(viewerId, p)) {
                        candidates.add(p);
                    }
                }
            }
            candidates.sort(ranking.order(now()));
            int from = Math.min(candidates.size(), page * pageSize);
            return List.copyOf(candidates.subList(from, Math.min(candidates.size(), from + pageSize)));
        });
    }

    // ------------------------------------------------------------------ graph queries

    public Set<String> friendsOf(String userId) {
        return read(() -> java.util.Collections.unmodifiableSet(new TreeSet<>(friends.get(user(userId).id()))));
    }

    public List<String> mutualFriends(String a, String b) {
        return read(() -> friends.get(user(a).id()).stream().filter(friends.get(user(b).id())::contains).toList());
    }

    /** People you may know: friends of friends, ranked by number of mutual friends, then id. */
    public List<String> suggestions(String userId, int limit) {
        return read(() -> {
            Set<String> mine = friends.get(user(userId).id());
            Map<String, Integer> mutualCount = new HashMap<>();
            for (String f : mine) {
                for (String fof : friends.get(f)) {
                    if (!fof.equals(userId) && !mine.contains(fof) && !isBlockedEitherWay(userId, fof)
                            && pending(userId, fof) == null) {
                        mutualCount.merge(fof, 1, Integer::sum);
                    }
                }
            }
            return mutualCount.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .limit(limit).map(Map.Entry::getKey).toList();
        });
    }

    /** Degrees of separation through friendships (BFS); -1 if not connected. */
    public int degreesOfSeparation(String from, String to) {
        return read(() -> {
            user(from);
            user(to);
            if (from.equals(to)) {
                return 0;
            }
            Map<String, Integer> dist = new HashMap<>();
            Deque<String> queue = new ArrayDeque<>();
            dist.put(from, 0);
            queue.add(from);
            while (!queue.isEmpty()) {
                String u = queue.poll();
                for (String v : friends.get(u)) {
                    if (!dist.containsKey(v)) {
                        dist.put(v, dist.get(u) + 1);
                        if (v.equals(to)) {
                            return dist.get(v);
                        }
                        queue.add(v);
                    }
                }
            }
            return -1;
        });
    }

    public List<String> notifications(String userId) {
        return read(() -> List.copyOf(inbox.getOrDefault(user(userId).id(), List.of())));
    }

    public boolean areFriends(String a, String b) {
        return read(() -> friends.getOrDefault(a, Set.of()).contains(b));
    }

    // ------------------------------------------------------------------ internals

    private void acceptLocked(FriendRequest r) {
        r.setStatus(FriendRequest.Status.ACCEPTED);
        friends.get(r.fromId()).add(r.toId());
        friends.get(r.toId()).add(r.fromId());
        notifyLocked(r.fromId(), name(r.toId()) + " accepted your friend request");
    }

    private FriendRequest requestFor(String receiverId, String requestId) {
        FriendRequest r = requests.get(requestId);
        if (r == null || !r.toId().equals(receiverId) || r.status() != FriendRequest.Status.PENDING) {
            throw new SocialException("No pending request " + requestId + " for " + receiverId);
        }
        return r;
    }

    private FriendRequest pending(String from, String to) {
        return requests.values().stream()
                .filter(r -> r.fromId().equals(from) && r.toId().equals(to) && r.status() == FriendRequest.Status.PENDING)
                .findFirst().orElse(null);
    }

    private Post postVisibleTo(String userId, String postId) {
        user(userId);
        Post p = posts.get(postId);
        if (p == null || !canSee(userId, p)) {
            throw new SocialException("No post " + postId);                     // don't reveal hidden posts exist
        }
        return p;
    }

    private boolean isBlockedEitherWay(String a, String b) {
        return blocked.getOrDefault(a, Set.of()).contains(b) || blocked.getOrDefault(b, Set.of()).contains(a);
    }

    private void notifyLocked(String userId, String text) {
        inbox.computeIfAbsent(userId, k -> new ArrayList<>()).add(text);
        listeners.forEach(l -> l.notify(userId, text));
    }

    private User user(String id) {
        User u = users.get(id);
        if (u == null) {
            throw new SocialException("No user " + id);
        }
        return u;
    }

    private String name(String id) {
        User u = users.get(id);
        return u == null ? id : u.name();
    }

    private Instant now() {
        return clock.instant();
    }

    private <T> T read(Supplier<T> action) {
        rw.readLock().lock();
        try {
            return action.get();
        } finally {
            rw.readLock().unlock();
        }
    }

    private <T> T write(Supplier<T> action) {
        rw.writeLock().lock();
        try {
            return action.get();
        } finally {
            rw.writeLock().unlock();
        }
    }
}
