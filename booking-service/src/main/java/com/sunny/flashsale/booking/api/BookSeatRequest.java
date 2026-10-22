package com.sunny.flashsale.booking.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * @param seatNumber optional; omit it to be given any available seat
 */
public record BookSeatRequest(
        @NotNull @Positive Long eventId,
        @Size(max = 16) String seatNumber) {
}
