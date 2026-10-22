package com.sunny.flashsale.booking.service;

import java.time.LocalDateTime;

/**
 * Moves one seat AVAILABLE -> HELD, or throws {@code SEAT_UNAVAILABLE}.
 * Must be called inside the caller's transaction: the seat lock/version check has to live
 * exactly as long as the booking insert that follows it.
 */
public interface SeatHolder {

    LockingStrategy strategy();

    void hold(long seatId, String userId, LocalDateTime until);
}
