package com.lld.social.learning.model;

/** Anyone can teach and learn; the role comes from what they do (own a course vs enroll in one). */
public record User(String id, String name) {

    @Override
    public String toString() {
        return name;
    }
}
