package com.lld.social.network.model;

import java.time.Instant;

public record Comment(String authorId, String text, Instant at) {
}
