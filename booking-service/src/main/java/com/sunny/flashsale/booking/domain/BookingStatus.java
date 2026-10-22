package com.sunny.flashsale.booking.domain;

/**
 * Mirrors the CHECK constraint on bookings.status.
 * "Live" statuses (PENDING_PAYMENT, CONFIRMED) are the ones that occupy a seat in the
 * uq_booking_active_seat unique index; see V1__booking_schema.sql.
 */
public enum BookingStatus {
    PENDING_PAYMENT,
    CONFIRMED,
    CANCELLED,
    EXPIRED
}
