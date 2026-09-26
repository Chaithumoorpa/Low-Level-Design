package com.lld.finance.splitwise.model;

import java.time.Instant;

/** A real-world payment recorded in the app: "Raj paid Ana $30". */
public record Settlement(String id, String groupId, String fromId, String toId, long amountCents, Instant at) {
}
