package com.sunny.flashsale.common.events;

/** Outcome of a payment, as announced by payment-service. Consumers must ignore types they do not care about. */
public enum PaymentEventType {
    PAYMENT_SUCCEEDED,
    PAYMENT_FAILED,
    PAYMENT_REFUNDED
}
