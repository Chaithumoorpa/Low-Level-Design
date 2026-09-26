package com.lld.social.qa.model;

import java.time.Instant;

/** One saved version of a post; the full list is the edit history. */
public record Revision(int number, String editorId, String title, String body, String summary, Instant at) {
}
