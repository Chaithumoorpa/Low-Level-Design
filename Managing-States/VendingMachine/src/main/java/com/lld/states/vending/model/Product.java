package com.lld.states.vending.model;

/** What is sold. Prices are in the smallest currency unit (cents) to avoid floating-point money. */
public record Product(String name, int price) {

    public Product {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Product name is required");
        }
        if (price <= 0) {
            throw new IllegalArgumentException("Price must be positive");
        }
    }

    public Product withPrice(int newPrice) {
        return new Product(name, newPrice);
    }

    @Override
    public String toString() {
        return name + " " + Money.format(price);
    }
}
