package com.lld.management.inventory.model;

public record Warehouse(String id, String name, Location location) {

    @Override
    public String toString() {
        return id;
    }
}
