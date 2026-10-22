package com.sunny.flashsale.common.events;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The message published on {@link Topics#PAYMENT_EVENTS}. {@code bookingId} is the saga's correlation id: it ties the
 * payment back to the booking it belongs to.
 *
 * @param eventId unique id of THIS message (consumers de-duplicate on it)
 * @param reason  why the payment failed or was refunded; null on success
 */
public record PaymentEvent(
        String eventId,
        PaymentEventType type,
        Instant occurredAt,
        String bookingId,
        String userId,
        BigDecimal amount,
        String reason) {
}
