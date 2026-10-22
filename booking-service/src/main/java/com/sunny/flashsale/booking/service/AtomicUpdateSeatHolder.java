package com.sunny.flashsale.booking.service;

import com.sunny.flashsale.booking.repository.SeatRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * UPDATE seats SET status='HELD' ... WHERE id=? AND status='AVAILABLE'.
 * InnoDB takes the row lock for the UPDATE and re-evaluates the WHERE after any wait, so of N concurrent
 * callers exactly one sees "1 row affected". No read-then-write gap, no version column, one round trip.
 */
@Component
public class AtomicUpdateSeatHolder implements SeatHolder {

    private final SeatRepository seats;

    public AtomicUpdateSeatHolder(SeatRepository seats) {
        this.seats = seats;
    }

    @Override
    public LockingStrategy strategy() {
        return LockingStrategy.ATOMIC_UPDATE;
    }

    @Override
    public void hold(long seatId, String userId, LocalDateTime until) {
        if (seats.tryHold(seatId, userId, until) == 0) {
            throw BookingErrors.seatUnavailable();
        }
    }
}
