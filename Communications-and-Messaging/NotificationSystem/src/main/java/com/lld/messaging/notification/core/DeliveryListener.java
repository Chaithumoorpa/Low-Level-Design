package com.lld.messaging.notification.core;

import com.lld.messaging.notification.model.Delivery;

/** Observer for delivery outcomes: metrics, audit, alerting on provider outages. */
public interface DeliveryListener {

    default void onSent(Delivery delivery) {
    }

    /** Gave up (permanent error or out of attempts). */
    default void onFailed(Delivery delivery) {
    }

    default void onSkipped(Delivery delivery) {
    }
}
