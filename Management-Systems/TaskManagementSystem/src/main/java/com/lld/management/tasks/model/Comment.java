package com.lld.management.tasks.model;

import java.time.Instant;

public record Comment(User author, String text, Instant at) {
}
