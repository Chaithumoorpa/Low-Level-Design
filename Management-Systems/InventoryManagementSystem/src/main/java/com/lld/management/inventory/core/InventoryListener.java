package com.lld.management.inventory.core;

import com.lld.management.inventory.model.PurchaseOrder;
import com.lld.management.inventory.model.Reservation;
import com.lld.management.inventory.model.StockLevel;

/** Observer for stock events: dashboards, supplier integration, storefront "only 2 left" badges. */
public interface InventoryListener {

    default void onReserved(Reservation reservation) {
    }

    default void onShipped(Reservation reservation) {
    }

    default void onReleased(Reservation reservation) {
    }

    /** Available stock hit the reorder point; a purchase order was raised. */
    default void onLowStock(StockLevel level, PurchaseOrder raised) {
    }

    default void onOutOfStock(String warehouseId, String sku) {
    }
}
