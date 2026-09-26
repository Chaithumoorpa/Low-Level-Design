package com.lld.management.restaurant.core;

import com.lld.management.restaurant.billing.Bill;
import com.lld.management.restaurant.billing.BillCalculator;
import com.lld.management.restaurant.model.Booking;
import com.lld.management.restaurant.model.MenuItem;
import com.lld.management.restaurant.model.OrderItem;
import com.lld.management.restaurant.model.RestaurantException;
import com.lld.management.restaurant.model.Tab;
import com.lld.management.restaurant.model.Table;
import com.lld.management.restaurant.seating.TableAssignmentStrategy;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Facade for front of house (host stand, servers, cashier) and back of house (kitchen stations).
 *
 * <ul>
 *   <li><b>Seating</b>: bookings hold a table for a slot; walk-ins only get tables that are free now
 *       <em>and</em> not booked within the next slot.</li>
 *   <li><b>Orders</b>: one order is split into one ticket per kitchen station; each station works
 *       first in, first out.</li>
 *   <li><b>Billing</b>: a {@link BillCalculator} with pluggable rules; payments may be split; the tab
 *       closes only when fully paid and nothing is still coming from the kitchen.</li>
 * </ul>
 *
 * <p>One lock guards all state (a restaurant has tens of tables, not millions); events for screens
 * and handhelds are published after it is released.
 */
public final class Restaurant {

    /** One line on the server's handheld. */
    public record Line(String menuItemId, int quantity, String note) {

        public static Line of(String menuItemId, int quantity) {
            return new Line(menuItemId, quantity, "");
        }
    }

    private final Object lock = new Object();
    private final Map<String, MenuItem> menu = new LinkedHashMap<>();
    private final Set<String> soldOut = new HashSet<>();
    private final Map<String, Table> tables = new LinkedHashMap<>();
    private final List<Booking> bookings = new ArrayList<>();
    private final Map<String, Tab> openTabByTable = new HashMap<>();
    private final Map<String, Booking> bookingByTab = new HashMap<>();
    private final Map<String, OrderItem> items = new HashMap<>();
    private final Map<MenuItem.Station, Deque<OrderItem>> stationQueues = new EnumMap<>(MenuItem.Station.class);
    private final List<RestaurantListener> listeners = new CopyOnWriteArrayList<>();
    private long bookingSeq;
    private long tabSeq;
    private long itemSeq;

    private final TableAssignmentStrategy seating;
    private final BillCalculator billing;
    private final Duration slot;
    private final Duration lateGrace;
    private final Clock clock;

    public Restaurant(TableAssignmentStrategy seating, BillCalculator billing, Duration slot, Duration lateGrace,
                      Clock clock) {
        this.seating = Objects.requireNonNull(seating);
        this.billing = Objects.requireNonNull(billing);
        this.slot = slot;
        this.lateGrace = lateGrace;
        this.clock = Objects.requireNonNull(clock);
        for (MenuItem.Station s : MenuItem.Station.values()) {
            stationQueues.put(s, new ArrayDeque<>());
        }
    }

    public void addListener(RestaurantListener listener) {
        listeners.add(listener);
    }

    // ------------------------------------------------------------------ setup

    public void addMenuItem(MenuItem item) {
        synchronized (lock) {
            if (menu.putIfAbsent(item.id(), item) != null) {
                throw new RestaurantException("Duplicate menu item " + item.id());
            }
        }
    }

    /** "86" a dish when the kitchen runs out, or bring it back. */
    public void setSoldOut(String menuItemId, boolean soldOutNow) {
        synchronized (lock) {
            menuItem(menuItemId);
            if (soldOutNow) {
                soldOut.add(menuItemId);
            } else {
                soldOut.remove(menuItemId);
            }
        }
    }

    public void addTable(Table table) {
        synchronized (lock) {
            if (tables.putIfAbsent(table.id(), table) != null) {
                throw new RestaurantException("Duplicate table " + table.id());
            }
        }
    }

    // ------------------------------------------------------------------ seating

    public Booking book(String guestName, int partySize, LocalDateTime start) {
        synchronized (lock) {
            if (start.isBefore(now())) {
                throw new RestaurantException("Can't book in the past");
            }
            LocalDateTime end = start.plus(slot);
            List<Table> candidates = tables.values().stream()
                    .filter(t -> t.seats() >= partySize)
                    .filter(t -> !bookedDuring(t, start, end))
                    .toList();
            Table table = seating.choose(partySize, candidates).orElseThrow(() -> new RestaurantException(
                    "No table for " + partySize + " at " + start.toLocalTime()));
            Booking b = new Booking("B" + (++bookingSeq), guestName, partySize, table, start, end);
            bookings.add(b);
            return b;
        }
    }

    public void cancelBooking(String bookingId) {
        synchronized (lock) {
            Booking b = booking(bookingId);
            if (b.status() != Booking.Status.BOOKED) {
                throw new RestaurantException(bookingId + " is " + b.status());
            }
            b.setStatus(Booking.Status.CANCELLED);
        }
    }

    /** Guests with a booking arrive. Too late (after the grace period) counts as a no-show. */
    public Tab checkIn(String bookingId) {
        synchronized (lock) {
            Booking b = booking(bookingId);
            if (b.status() != Booking.Status.BOOKED) {
                throw new RestaurantException(bookingId + " is " + b.status());
            }
            if (now().isAfter(b.start().plus(lateGrace))) {
                throw new RestaurantException(bookingId + " was for " + b.start().toLocalTime()
                        + "; more than " + lateGrace.toMinutes() + " min late");
            }
            if (b.table().status() != Table.Status.FREE) {
                throw new RestaurantException(b.table().id() + " is still " + b.table().status() + "; please wait at the bar");
            }
            b.setStatus(Booking.Status.SEATED);
            Tab tab = seat(b.table(), b.partySize());
            bookingByTab.put(tab.id(), b);
            return tab;
        }
    }

    /** Walk-in: a free table that nobody has booked for the next slot. */
    public Tab seatWalkIn(int partySize) {
        synchronized (lock) {
            LocalDateTime from = now();
            LocalDateTime to = from.plus(slot);
            List<Table> candidates = tables.values().stream()
                    .filter(t -> t.status() == Table.Status.FREE && t.seats() >= partySize)
                    .filter(t -> !bookedDuring(t, from, to))
                    .toList();
            Table table = seating.choose(partySize, candidates).orElseThrow(() -> new RestaurantException(
                    "No free table for " + partySize + " right now"));
            return seat(table, partySize);
        }
    }

    /** Host-stand job: bookings more than the grace period late release their table. */
    public List<Booking> markNoShows() {
        List<Booking> noShows;
        synchronized (lock) {
            LocalDateTime now = now();
            noShows = bookings.stream()
                    .filter(b -> b.status() == Booking.Status.BOOKED && now.isAfter(b.start().plus(lateGrace)))
                    .toList();
            noShows.forEach(b -> b.setStatus(Booking.Status.NO_SHOW));
        }
        noShows.forEach(b -> listeners.forEach(l -> l.onNoShow(b)));
        return noShows;
    }

    // ------------------------------------------------------------------ ordering & kitchen

    /** All lines or none (a sold-out dish rejects the whole order so the server can re-take it). */
    public List<OrderItem> order(String tableId, List<Line> lines) {
        Map<MenuItem.Station, List<OrderItem>> tickets = new EnumMap<>(MenuItem.Station.class);
        List<OrderItem> created = new ArrayList<>();
        synchronized (lock) {
            Tab tab = openTab(tableId);
            if (lines.isEmpty()) {
                throw new RestaurantException("Empty order");
            }
            for (Line l : lines) {
                MenuItem m = menuItem(l.menuItemId());
                if (soldOut.contains(m.id())) {
                    throw new RestaurantException(m.name() + " is sold out");
                }
            }
            LocalDateTime now = now();
            for (Line l : lines) {
                MenuItem m = menuItem(l.menuItemId());
                OrderItem item = new OrderItem("I" + (++itemSeq), tableId, m, l.quantity(), l.note(), now);
                tab.add(item);
                items.put(item.id(), item);
                stationQueues.get(m.station()).addLast(item);
                tickets.computeIfAbsent(m.station(), k -> new ArrayList<>()).add(item);
                created.add(item);
            }
        }
        tickets.forEach((station, list) -> listeners.forEach(l -> l.onTicket(station, tableId, List.copyOf(list))));
        return created;
    }

    /** A cook takes the oldest waiting item at their station. */
    public Optional<OrderItem> startNext(MenuItem.Station station) {
        synchronized (lock) {
            OrderItem next = stationQueues.get(station).pollFirst();
            if (next != null) {
                next.moveTo(OrderItem.Status.PREPARING);
            }
            return Optional.ofNullable(next);
        }
    }

    public void markReady(String itemId) {
        OrderItem item;
        synchronized (lock) {
            item = item(itemId);
            move(item, OrderItem.Status.READY);
        }
        listeners.forEach(l -> l.onItemReady(item));
    }

    public void markServed(String itemId) {
        synchronized (lock) {
            move(item(itemId), OrderItem.Status.SERVED);
        }
    }

    /** Server takes an item back before the cook starts it: removed from the bill. */
    public void cancelItem(String itemId) {
        synchronized (lock) {
            OrderItem item = item(itemId);
            if (item.status() != OrderItem.Status.PLACED) {
                throw new RestaurantException(item.item().name() + " is already " + item.status()
                        + "; ask a manager to void it");
            }
            item.moveTo(OrderItem.Status.CANCELLED);
            stationQueues.get(item.item().station()).remove(item);
        }
    }

    /** Manager comp: the item stays on the bill at $0 with the reason. */
    public void voidItem(String itemId, String reason) {
        synchronized (lock) {
            OrderItem item = item(itemId);
            if (reason == null || reason.isBlank()) {
                throw new RestaurantException("A void needs a reason");
            }
            try {
                item.voidItem(reason);
            } catch (IllegalStateException e) {
                throw new RestaurantException(e.getMessage());
            }
            stationQueues.get(item.item().station()).remove(item);
        }
    }

    // ------------------------------------------------------------------ billing

    public Bill bill(String tableId) {
        synchronized (lock) {
            return billing.bill(openTab(tableId));
        }
    }

    /**
     * Takes a payment (one of possibly several for a split bill). When the bill is covered the tab
     * closes and the table goes to CLEANING; anything above the total is recorded as a tip.
     *
     * @return amount still due (0 when settled)
     */
    public long pay(String tableId, long cents) {
        synchronized (lock) {
            if (cents <= 0) {
                throw new RestaurantException("Payment must be positive");
            }
            Tab tab = openTab(tableId);
            long total = billing.bill(tab).totalCents();
            long due = total - tab.paidCents();
            if (cents >= due) {
                long inProgress = tab.items().stream().filter(i -> i.status().inProgress()).count();
                if (inProgress > 0) {
                    throw new RestaurantException(inProgress + " item(s) still on their way; settle after they are served");
                }
            }
            tab.addPayment(cents);
            if (tab.paidCents() >= total) {
                tab.close(now());
                openTabByTable.remove(tableId);
                tab.table().setStatus(Table.Status.CLEANING);
                Booking b = bookingByTab.remove(tab.id());
                if (b != null) {
                    b.setStatus(Booking.Status.COMPLETED);
                }
                return 0;
            }
            return total - tab.paidCents();
        }
    }

    public void markClean(String tableId) {
        Table table;
        synchronized (lock) {
            table = table(tableId);
            if (table.status() != Table.Status.CLEANING) {
                throw new RestaurantException(tableId + " is " + table.status());
            }
            table.setStatus(Table.Status.FREE);
        }
        listeners.forEach(l -> l.onTableFree(table));
    }

    // ------------------------------------------------------------------ queries

    public Tab tab(String tableId) {
        synchronized (lock) {
            return openTab(tableId);
        }
    }

    public Table table(String tableId) {
        synchronized (lock) {
            Table t = tables.get(tableId);
            if (t == null) {
                throw new RestaurantException("No table " + tableId);
            }
            return t;
        }
    }

    public List<OrderItem> queue(MenuItem.Station station) {
        synchronized (lock) {
            return List.copyOf(stationQueues.get(station));
        }
    }

    public List<Booking> bookings() {
        synchronized (lock) {
            return List.copyOf(bookings);
        }
    }

    public Set<String> soldOutItems() {
        synchronized (lock) {
            return new LinkedHashSet<>(soldOut);
        }
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    // ------------------------------------------------------------------ internals

    private Tab seat(Table table, int partySize) {
        if (partySize > table.seats()) {
            throw new RestaurantException(table + " is too small for " + partySize);
        }
        table.setStatus(Table.Status.OCCUPIED);
        Tab tab = new Tab("TAB" + (++tabSeq), table, partySize, now());
        openTabByTable.put(table.id(), tab);
        return tab;
    }

    /** A booked or currently seated booking holds its table for its whole slot. */
    private boolean bookedDuring(Table table, LocalDateTime from, LocalDateTime to) {
        return bookings.stream().anyMatch(b -> b.table().equals(table) && b.isActive() && b.overlaps(from, to));
    }

    private void move(OrderItem item, OrderItem.Status target) {
        try {
            item.moveTo(target);
        } catch (IllegalStateException e) {
            throw new RestaurantException(e.getMessage());
        }
    }

    private Tab openTab(String tableId) {
        table(tableId);
        Tab tab = openTabByTable.get(tableId);
        if (tab == null) {
            throw new RestaurantException("Nobody is seated at " + tableId);
        }
        return tab;
    }

    private MenuItem menuItem(String id) {
        MenuItem m = menu.get(id);
        if (m == null) {
            throw new RestaurantException("No menu item " + id);
        }
        return m;
    }

    private OrderItem item(String id) {
        OrderItem i = items.get(id);
        if (i == null) {
            throw new RestaurantException("No order item " + id);
        }
        return i;
    }

    private Booking booking(String id) {
        return bookings.stream().filter(b -> b.id().equals(id)).findFirst()
                .orElseThrow(() -> new RestaurantException("No booking " + id));
    }
}
