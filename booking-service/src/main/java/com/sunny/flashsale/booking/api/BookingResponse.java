package com.sunny.flashsale.booking.api;

import com.sunny.flashsale.booking.domain.Booking;
import com.sunny.flashsale.booking.domain.BookingStatus;

import java.math.BigDecimal;

/** What clients see. A separate type from the entity so the API can evolve without touching the schema. */
public record BookingResponse(
        String id,
        long eventId,
        long seatId,
        BookingStatus status,
        BigDecimal amount,
        String failureReason) {

    public static BookingResponse from(Booking b) {
        return new BookingResponse(b.getId(), b.getEventId(), b.getSeatId(), b.getStatus(), b.getAmount(), b.getFailureReason());
    }
}
