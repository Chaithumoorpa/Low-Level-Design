package com.lld.messaging.notification.template;

/** Final text for one channel. {@code subject} is empty for channels without one (SMS). */
public record RenderedMessage(String subject, String body) {
}
