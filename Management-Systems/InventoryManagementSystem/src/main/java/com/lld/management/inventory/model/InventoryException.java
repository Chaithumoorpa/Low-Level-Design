package com.lld.management.inventory.model;

/** A request the inventory can't honour: not enough stock, unknown SKU, expired reservation... */
public class InventoryException extends RuntimeException {

    public InventoryException(String message) {
        super(message);
    }
}
