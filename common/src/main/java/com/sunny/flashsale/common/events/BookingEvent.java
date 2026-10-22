package com.sunny.flashsale.common.events;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The message published on {@link Topics#BOOKING_EVENTS}. Lives in {@code common} so producer and consumers share one
 * contract. Only additive changes are safe (new optional fields): old consumers ignore unknown fields.
 *
 * @param eventId     unique id of THIS message; consumers de-duplicate on it (Kafka delivery is at-least-once)
 * @param saleEventId the flash sale (concert) the seat belongs to; named so it is not confused with {@code eventId}
 * @param reason      why a booking was cancelled; null for other types
 */
public record BookingEvent(
        String eventId,
        BookingEventType type,
        Instant occurredAt,
        String bookingId,
        String userId,
        long saleEventId,
        long seatId,
        BigDecimal amount,
        String reason) {
}
