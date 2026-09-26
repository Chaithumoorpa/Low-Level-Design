package com.lld.social.qa.model;

import java.time.Instant;

/** Short remark under a post ("could you share the stack trace?"). Not voted for reputation here. */
public record Comment(String id, String authorId, String text, Instant at) {
}
