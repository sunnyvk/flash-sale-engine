package com.sunny.flashsale.common.events;

/** What happened to a booking. Consumers must ignore types they do not care about instead of failing. */
public enum BookingEventType {
    BOOKING_HELD,
    BOOKING_CONFIRMED,
    BOOKING_CANCELLED
}
