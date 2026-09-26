package com.lld.messaging.chat.model;

public record User(String id, String name) {

    @Override
    public String toString() {
        return name;
    }
}
