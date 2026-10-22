package com.sunny.flashsale.booking.repository;

import com.sunny.flashsale.booking.domain.Booking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, String> {

    /** Backed by the unique index uq_booking_user_idempotency, so this is a single index lookup. */
    Optional<Booking> findByUserIdAndIdempotencyKey(String userId, String idempotencyKey);
}
