package com.sunny.flashsale.booking.service;

import com.sunny.flashsale.booking.domain.Seat;
import com.sunny.flashsale.booking.repository.SeatRepository;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Read the seat, change it in memory, write back with "WHERE id=? AND version=?".
 * If someone else got there first, 0 rows match and Hibernate throws an optimistic-lock failure.
 * For a seat the answer to "lost the race" is simply "seat taken", so we do not retry; retrying would only
 * re-read a HELD seat. Optimistic locking shines when conflicts are rare, which is the opposite of a flash sale.
 */
@Component
public class OptimisticSeatHolder implements SeatHolder {

    private final SeatRepository seats;

    public OptimisticSeatHolder(SeatRepository seats) {
        this.seats = seats;
    }

    @Override
    public LockingStrategy strategy() {
        return LockingStrategy.OPTIMISTIC;
    }

    @Override
    public void hold(long seatId, String userId, LocalDateTime until) {
        Seat seat = seats.findById(seatId).orElseThrow(BookingErrors::seatUnavailable);
        if (!seat.isAvailable()) {
            throw BookingErrors.seatUnavailable();
        }
        seat.hold(userId, until);
        try {
            // Flush NOW so the version check happens here, where we can translate it, not at commit time.
            seats.saveAndFlush(seat);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw BookingErrors.seatUnavailable();
        }
    }
}
