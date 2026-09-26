package com.lld.finance.splitwise.model;

public record User(String id, String name) {

    @Override
    public String toString() {
        return name;
    }
}
