package com.lld.social.learning.model;

import java.time.Instant;

/** Proof of completion. Employers check it on the public verification page by its code. */
public record Certificate(String id, String studentId, String courseId, String courseTitle, Instant issuedAt,
                          String verificationCode) {
}
