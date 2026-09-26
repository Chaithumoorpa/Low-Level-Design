package com.lld.social.learning.model;

import java.util.List;

/**
 * A unit of content. Videos and articles are completed by the student marking them done; a quiz is
 * completed only by passing it.
 */
public sealed interface Lesson {

    String id();

    String title();

    record Video(String id, String title, int minutes) implements Lesson {
    }

    record Article(String id, String title, int words) implements Lesson {
    }

    /** Multiple choice. {@code passPercent} of answers must be right; at most {@code maxAttempts} tries. */
    record Quiz(String id, String title, List<QuizQuestion> questions, int passPercent, int maxAttempts) implements Lesson {
        public Quiz {
            questions = List.copyOf(questions);
            if (questions.isEmpty() || passPercent < 1 || passPercent > 100 || maxAttempts < 1) {
                throw new IllegalArgumentException("Invalid quiz " + id);
            }
        }
    }

    record QuizQuestion(String prompt, List<String> options, int correctIndex) {
        public QuizQuestion {
            options = List.copyOf(options);
            if (correctIndex < 0 || correctIndex >= options.size()) {
                throw new IllegalArgumentException("Correct option out of range");
            }
        }
    }
}
