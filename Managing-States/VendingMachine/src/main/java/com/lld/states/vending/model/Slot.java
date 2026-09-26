package com.lld.states.vending.model;

/**
 * One spiral/column in the machine, addressed by a code like "A1". Holds one product type,
 * up to a fixed capacity.
 */
public class Slot {

    private final String code;
    private final int capacity;
    private Product product;
    private int quantity;

    public Slot(String code, Product product, int capacity) {
        if (code == null || !code.matches("[A-Z]\\d")) {
            throw new IllegalArgumentException("Slot code must look like A1");
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.code = code;
        this.product = product;
        this.capacity = capacity;
    }

    public String code() {
        return code;
    }

    public Product product() {
        return product;
    }

    public int quantity() {
        return quantity;
    }

    public int capacity() {
        return capacity;
    }

    public boolean isSoldOut() {
        return quantity == 0;
    }

    /** @return how many were actually added (the rest does not fit) */
    public int restock(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Negative restock");
        }
        int added = Math.min(count, capacity - quantity);
        quantity += added;
        return added;
    }

    public void takeOne() {
        if (quantity == 0) {
            throw new IllegalStateException(code + " is empty");
        }
        quantity--;
    }

    public void setPrice(int price) {
        product = product.withPrice(price);
    }

    @Override
    public String toString() {
        return code + ": " + product + (isSoldOut() ? " (SOLD OUT)" : " x" + quantity);
    }
}
