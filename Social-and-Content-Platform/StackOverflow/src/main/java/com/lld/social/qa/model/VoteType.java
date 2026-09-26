package com.lld.social.qa.model;

public enum VoteType {
    UP(1), DOWN(-1);

    private final int value;

    VoteType(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }
}
