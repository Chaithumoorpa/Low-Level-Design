package com.lld.messaging.notification.model;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What a caller (order service, auth service, marketing tool) asks for: "tell user X about Y".
 * The caller never picks addresses or builds text; it names a template and passes variables.
 */
public final class NotificationRequest {

    private final String userId;
    private final String templateId;
    private final Map<String, String> variables;
    private final Category category;
    private final Priority priority;
    private final String idempotencyKey;
    private final Set<Channel> channels;

    private NotificationRequest(Builder b) {
        this.userId = Objects.requireNonNull(b.userId, "userId");
        this.templateId = Objects.requireNonNull(b.templateId, "templateId");
        this.variables = Map.copyOf(b.variables);
        this.category = Objects.requireNonNull(b.category, "category");
        this.priority = b.priority == null ? b.category.defaultPriority() : b.priority;
        this.idempotencyKey = b.idempotencyKey;
        this.channels = b.channels == null ? null : EnumSet.copyOf(b.channels);
    }

    public static Builder to(String userId) {
        return new Builder(userId);
    }

    public String userId() {
        return userId;
    }

    public String templateId() {
        return templateId;
    }

    public Map<String, String> variables() {
        return variables;
    }

    public Category category() {
        return category;
    }

    public Priority priority() {
        return priority;
    }

    /** Same key twice = same notification (safe retries by the caller). May be null. */
    public String idempotencyKey() {
        return idempotencyKey;
    }

    /** Explicit channels, or null to use the user's preferences for the category. */
    public Set<Channel> channels() {
        return channels == null ? null : EnumSet.copyOf(channels);
    }

    public static final class Builder {
        private final String userId;
        private String templateId;
        private final Map<String, String> variables = new HashMap<>();
        private Category category;
        private Priority priority;
        private String idempotencyKey;
        private Set<Channel> channels;

        private Builder(String userId) {
            this.userId = userId;
        }

        public Builder template(String templateId) {
            this.templateId = templateId;
            return this;
        }

        public Builder with(String name, String value) {
            variables.put(name, value);
            return this;
        }

        public Builder category(Category category) {
            this.category = category;
            return this;
        }

        public Builder priority(Priority priority) {
            this.priority = priority;
            return this;
        }

        public Builder idempotencyKey(String key) {
            this.idempotencyKey = key;
            return this;
        }

        public Builder only(Channel first, Channel... more) {
            this.channels = EnumSet.of(first, more);
            return this;
        }

        public NotificationRequest build() {
            return new NotificationRequest(this);
        }
    }
}
