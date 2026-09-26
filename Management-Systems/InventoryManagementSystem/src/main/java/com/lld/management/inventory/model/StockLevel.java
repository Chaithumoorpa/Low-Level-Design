package com.lld.management.inventory.model;

/**
 * Snapshot of one SKU in one warehouse.
 * <ul>
 *   <li><b>onHand</b>: physically on the shelves,</li>
 *   <li><b>reserved</b>: promised to orders that haven't shipped yet,</li>
 *   <li><b>available</b> = onHand - reserved: what a new order can still get.</li>
 * </ul>
 */
public record StockLevel(String warehouseId, String sku, int onHand, int reserved) {

    public int available() {
        return onHand - reserved;
    }
}
