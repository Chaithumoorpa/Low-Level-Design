package com.lld.messaging.notification.template;

import com.lld.messaging.notification.model.Channel;
import com.lld.messaging.notification.model.NotificationException;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One message in several channel formats: a long e-mail, a 160-character SMS, a short push. Text uses
 * {@code {placeholders}}; a missing variable is an error (never send "Hi {name}" to a customer).
 */
public final class Template {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");
    public static final int SMS_LIMIT = 160;

    private record Part(String subject, String body) {
    }

    private final String id;
    private final Map<Channel, Part> parts = new EnumMap<>(Channel.class);

    private Template(String id) {
        this.id = id;
    }

    public static Template named(String id) {
        return new Template(id);
    }

    public Template channel(Channel channel, String subject, String body) {
        parts.put(channel, new Part(subject == null ? "" : subject, body));
        return this;
    }

    public String id() {
        return id;
    }

    public Set<Channel> channels() {
        return parts.keySet();
    }

    /** @return the text for the channel, or empty if this template has no version for it */
    public Optional<RenderedMessage> render(Channel channel, Map<String, String> variables) {
        Part p = parts.get(channel);
        if (p == null) {
            return Optional.empty();
        }
        RenderedMessage m = new RenderedMessage(fill(p.subject(), variables), fill(p.body(), variables));
        if (channel == Channel.SMS && m.body().length() > SMS_LIMIT) {
            throw new NotificationException("SMS for " + id + " is " + m.body().length() + " chars (max " + SMS_LIMIT + ")");
        }
        return Optional.of(m);
    }

    private String fill(String text, Map<String, String> variables) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = variables.get(m.group(1));
            if (value == null) {
                throw new NotificationException("Template " + id + " needs variable '" + m.group(1) + "'");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
