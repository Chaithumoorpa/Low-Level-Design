package com.lld.finance.payments.core;

import com.lld.finance.payments.model.Payment;

/**
 * Observer: events for merchants ("payment.captured"...). In production these become signed HTTP
 * callbacks with retries (see the Notification System in the Communications folder).
 */
@FunctionalInterface
public interface WebhookListener {

    void onEvent(String merchantId, String type, Payment payment);
}
