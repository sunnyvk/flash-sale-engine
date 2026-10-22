package com.sunny.flashsale.booking.service;

import com.sunny.flashsale.common.error.ApiException;

/** Stable error codes in one place so the controller, tests and the k6 script agree on them. */
public final class BookingErrors {

    public static final String EVENT_NOT_FOUND = "EVENT_NOT_FOUND";
    public static final String SEAT_NOT_FOUND = "SEAT_NOT_FOUND";
    public static final String SEAT_UNAVAILABLE = "SEAT_UNAVAILABLE";
    public static final String NO_SEAT_AVAILABLE = "NO_SEAT_AVAILABLE";
    public static final String BOOKING_NOT_FOUND = "BOOKING_NOT_FOUND";
    public static final String DUPLICATE_REQUEST = "DUPLICATE_REQUEST";
    public static final String INVALID_BOOKING_STATE = "INVALID_BOOKING_STATE";
    public static final String INVALID_IDEMPOTENCY_KEY = "INVALID_IDEMPOTENCY_KEY";
    public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";
    public static final String REQUEST_IN_PROGRESS = "REQUEST_IN_PROGRESS";

    private BookingErrors() {
    }

    public static ApiException seatUnavailable() {
        return ApiException.conflict(SEAT_UNAVAILABLE, "Seat is already held or booked");
    }

    public static ApiException noSeatAvailable() {
        return ApiException.conflict(NO_SEAT_AVAILABLE, "No seat available right now; sold out or try again");
    }
}
