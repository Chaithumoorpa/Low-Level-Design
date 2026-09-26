package com.lld.messaging.notification.policy;

import com.lld.messaging.notification.model.Category;
import com.lld.messaging.notification.model.Channel;
import com.lld.messaging.notification.model.NotificationException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * A user's choices: which channels per category, and optional quiet hours (no non-urgent messages at
 * night; they are held until the morning, not dropped). Security alerts can't be turned off.
 */
public final class Preferences {

    private final Map<Category, Set<Channel>> channels = new EnumMap<>(Category.class);
    private LocalTime quietFrom;
    private LocalTime quietUntil;

    public static Preferences defaults() {
        Preferences p = new Preferences();
        p.channels.put(Category.SECURITY, EnumSet.of(Channel.EMAIL, Channel.SMS, Channel.PUSH));
        p.channels.put(Category.TRANSACTIONAL, EnumSet.of(Channel.EMAIL, Channel.PUSH, Channel.IN_APP));
        p.channels.put(Category.MARKETING, EnumSet.of(Channel.EMAIL));
        return p;
    }

    public synchronized Set<Channel> channelsFor(Category category) {
        Set<Channel> chosen = channels.get(category);
        return chosen == null ? EnumSet.noneOf(Channel.class) : EnumSet.copyOf(chosen);
    }

    public synchronized boolean allows(Category category, Channel channel) {
        return category == Category.SECURITY || channels.getOrDefault(category, Set.of()).contains(channel);
    }

    public synchronized void setChannels(Category category, Set<Channel> chosen) {
        if (category == Category.SECURITY && chosen.isEmpty()) {
            throw new NotificationException("Security alerts can't be turned off");
        }
        channels.put(category, chosen.isEmpty() ? EnumSet.noneOf(Channel.class) : EnumSet.copyOf(chosen));
    }

    /** E.g. 22:00 to 07:00 (may cross midnight). Times are UTC here to keep the model small. */
    public synchronized void setQuietHours(LocalTime from, LocalTime until) {
        this.quietFrom = from;
        this.quietUntil = until;
    }

    /** @return {@code now} outside quiet hours, else the moment quiet hours end */
    public synchronized Instant quietUntil(Instant now) {
        if (quietFrom == null) {
            return now;
        }
        LocalDateTime t = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        LocalTime time = t.toLocalTime();
        LocalDate day = t.toLocalDate();
        boolean crossesMidnight = quietFrom.isAfter(quietUntil);
        boolean quiet = crossesMidnight
                ? !time.isBefore(quietFrom) || time.isBefore(quietUntil)
                : !time.isBefore(quietFrom) && time.isBefore(quietUntil);
        if (!quiet) {
            return now;
        }
        LocalDate endDay = crossesMidnight && !time.isBefore(quietFrom) ? day.plusDays(1) : day;
        return endDay.atTime(quietUntil).toInstant(ZoneOffset.UTC);
    }
}
