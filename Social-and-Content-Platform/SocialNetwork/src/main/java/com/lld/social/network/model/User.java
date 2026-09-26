package com.lld.social.network.model;

public record User(String id, String name) {

    @Override
    public String toString() {
        return name;
    }
}
