package com.sunny.flashsale.booking.idempotency;

import com.sunny.flashsale.booking.domain.Booking;

/** @param replayed true when this is the stored answer to an earlier identical request, not a new booking */
public record BookingResult(Booking booking, boolean replayed) {
}
