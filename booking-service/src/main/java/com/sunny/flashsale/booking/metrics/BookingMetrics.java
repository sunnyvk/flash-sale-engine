package com.sunny.flashsale.booking.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The business counters in one place, so metric names and label values are not scattered across the code base.
 * Labels are always a small fixed set (never a user id, booking id or free text): every distinct label combination
 * is a separate time series in Prometheus, and unbounded labels are how monitoring gets expensive.
 * Prometheus renders the dots as underscores and appends _total to counters (flashsale_booking_attempts_total).
 */
@Component
public class BookingMetrics {

    private final MeterRegistry registry;

    public BookingMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** One per POST /bookings. outcome: created | replayed | seat_unavailable | sold_out | in_progress | key_reused | rejected | error */
    public void attempt(String outcome) {
        registry.counter("flashsale.booking.attempts", "outcome", outcome).increment();
    }

    /** A booking state change after payment: to = confirmed | cancelled. */
    public void transition(String to) {
        registry.counter("flashsale.booking.transitions", "to", to).increment();
    }

    /**
     * The database unique index stopped a double booking that the application-level lock should have stopped.
     * Expected value: 0. Anything else is an alert.
     */
    public void dbBackstopTriggered() {
        registry.counter("flashsale.db.backstop").increment();
    }

    /** Bookings cancelled by the timeout reaper because no payment outcome arrived. */
    public void reaped(int count) {
        registry.counter("flashsale.reaper.cancelled").increment(count);
    }

    public void outboxPublished(int count) {
        registry.counter("flashsale.outbox.published").increment(count);
    }

    public void outboxPublishFailed() {
        registry.counter("flashsale.outbox.publish.failures").increment();
    }
}
