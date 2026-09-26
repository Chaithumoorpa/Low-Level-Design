package com.lld.messaging.chat.model;

/** The ticks next to a message: one grey, two grey, two blue. Ordered, so min() gives a group's status. */
public enum DeliveryStatus {
    SENT, DELIVERED, READ
}
