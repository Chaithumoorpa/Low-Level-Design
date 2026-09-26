package com.lld.management.restaurant.billing;

import java.util.List;

/** A computed bill: item lines, then adjustment lines in the order the rules ran, then the total. */
public record Bill(List<BillLine> itemLines, long itemsSubtotalCents, List<BillLine> adjustments, long totalCents) {

    public Bill {
        itemLines = List.copyOf(itemLines);
        adjustments = List.copyOf(adjustments);
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        itemLines.forEach(l -> sb.append("   ").append(l).append('\n'));
        sb.append("   ").append(new BillLine("Subtotal", itemsSubtotalCents)).append('\n');
        adjustments.forEach(l -> sb.append("   ").append(l).append('\n'));
        sb.append("   ").append(new BillLine("TOTAL", totalCents)).append('\n');
        return sb.toString();
    }

    static String money(long cents) {
        return (cents < 0 ? "-" : "") + String.format("$%d.%02d", Math.abs(cents) / 100, Math.abs(cents) % 100);
    }
}
