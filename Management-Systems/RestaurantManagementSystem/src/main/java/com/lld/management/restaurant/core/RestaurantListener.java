package com.lld.management.restaurant.core;

import com.lld.management.restaurant.model.Booking;
import com.lld.management.restaurant.model.MenuItem;
import com.lld.management.restaurant.model.OrderItem;
import com.lld.management.restaurant.model.Table;

import java.util.List;

/** Observer: kitchen display screens, the server's handheld, the host stand. */
public interface RestaurantListener {

    /** New items for a station's screen (one ticket per station per order). */
    default void onTicket(MenuItem.Station station, String tableId, List<OrderItem> items) {
    }

    /** Pick-up call for the server. */
    default void onItemReady(OrderItem item) {
    }

    default void onNoShow(Booking booking) {
    }

    /** Bussed and ready for the next party. */
    default void onTableFree(Table table) {
    }
}
