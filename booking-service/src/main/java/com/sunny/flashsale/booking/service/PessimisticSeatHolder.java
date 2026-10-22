package com.sunny.flashsale.booking.service;

import com.sunny.flashsale.booking.domain.Seat;
import com.sunny.flashsale.booking.repository.SeatRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * SELECT ... FOR UPDATE, check, modify. Every caller queues on the row lock, so there is no wasted work
 * and no retry logic, but under a flash sale the queue holds one DB connection per waiting request.
 * Hibernate flushes the change (and bumps @Version) when the surrounding transaction flushes.
 */
@Component
public class PessimisticSeatHolder implements SeatHolder {

    private final SeatRepository seats;

    public PessimisticSeatHolder(SeatRepository seats) {
        this.seats = seats;
    }

    @Override
    public LockingStrategy strategy() {
        return LockingStrategy.PESSIMISTIC;
    }

    @Override
    public void hold(long seatId, String userId, LocalDateTime until) {
        Seat seat = seats.findByIdForUpdate(seatId).orElseThrow(BookingErrors::seatUnavailable);
        if (!seat.isAvailable()) {
            throw BookingErrors.seatUnavailable();
        }
        seat.hold(userId, until);
    }
}
