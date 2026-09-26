package com.lld.management.inventory.model;

import java.time.Instant;

/**
 * One line of the stock ledger. The ledger is append-only: current stock is always the sum of its
 * movements, so any number on screen can be explained ("why do we have 7?") and audited.
 *
 * @param onHandDelta   change to physical stock (+ received, - shipped/lost)
 * @param reservedDelta change to the reserved quantity (+ reserve, - release/ship)
 * @param reference     what caused it: order id, purchase order id, count sheet...
 */
public record StockMovement(long sequence, Instant at, Type type, String warehouseId, String sku,
                            int onHandDelta, int reservedDelta, String reference) {

    public enum Type {
        RECEIVE, RESERVE, RELEASE, SHIP, TRANSFER_OUT, TRANSFER_IN, ADJUST
    }

    @Override
    public String toString() {
        return String.format("#%-3d %-12s %-8s %-7s onHand %+4d reserved %+4d  %s", sequence, type, warehouseId, sku,
                onHandDelta, reservedDelta, reference);
    }
}
