package com.lld.social.learning.model;

import java.time.Instant;

public record Review(String studentId, String courseId, int stars, String text, Instant at) {

    public Review {
        if (stars < 1 || stars > 5) {
            throw new LearningException("Stars must be 1 to 5");
        }
    }
}
