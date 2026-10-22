package com.sunny.flashsale.booking.service;

import com.sunny.flashsale.booking.domain.Seat;
import com.sunny.flashsale.booking.repository.SeatRepository;
import com.sunny.flashsale.common.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Branch logic of the three strategies. The real race between them is proven on MySQL in BookingConcurrencyTest. */
@ExtendWith(MockitoExtension.class)
class SeatHolderTest {

    private static final LocalDateTime UNTIL = LocalDateTime.of(2026, 10, 9, 10, 5);

    @Mock SeatRepository seats;
    @Mock Seat seat;

    // ---------------------------------------------------------- ATOMIC_UPDATE

    @Test
    @DisplayName("atomic: 1 row updated means we won")
    void atomicWins() {
        when(seats.tryHold(1L, "alice", UNTIL)).thenReturn(1);

        new AtomicUpdateSeatHolder(seats).hold(1L, "alice", UNTIL);

        verify(seats).tryHold(1L, "alice", UNTIL);
    }

    @Test
    @DisplayName("atomic: 0 rows updated means someone else holds it")
    void atomicLoses() {
        when(seats.tryHold(1L, "alice", UNTIL)).thenReturn(0);

        assertThatThrownBy(() -> new AtomicUpdateSeatHolder(seats).hold(1L, "alice", UNTIL))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(BookingErrors.SEAT_UNAVAILABLE));
    }

    // ------------------------------------------------------------ PESSIMISTIC

    @Test
    @DisplayName("pessimistic: locks the row, sees AVAILABLE, holds it")
    void pessimisticWins() {
        when(seats.findByIdForUpdate(1L)).thenReturn(Optional.of(seat));
        when(seat.isAvailable()).thenReturn(true);

        new PessimisticSeatHolder(seats).hold(1L, "alice", UNTIL);

        verify(seat).hold("alice", UNTIL);
    }

    @Test
    @DisplayName("pessimistic: after waiting for the lock the seat is no longer AVAILABLE")
    void pessimisticLoses() {
        when(seats.findByIdForUpdate(1L)).thenReturn(Optional.of(seat));
        when(seat.isAvailable()).thenReturn(false);

        assertThatThrownBy(() -> new PessimisticSeatHolder(seats).hold(1L, "bob", UNTIL))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(BookingErrors.SEAT_UNAVAILABLE));
        verify(seat, never()).hold("bob", UNTIL);
    }

    // ------------------------------------------------------------- OPTIMISTIC

    @Test
    @DisplayName("optimistic: version check passes, seat is held and flushed")
    void optimisticWins() {
        when(seats.findById(1L)).thenReturn(Optional.of(seat));
        when(seat.isAvailable()).thenReturn(true);

        new OptimisticSeatHolder(seats).hold(1L, "alice", UNTIL);

        verify(seat).hold("alice", UNTIL);
        verify(seats).saveAndFlush(seat);
    }

    @Test
    @DisplayName("optimistic: version mismatch at flush time becomes SEAT_UNAVAILABLE")
    void optimisticLosesAtFlush() {
        when(seats.findById(1L)).thenReturn(Optional.of(seat));
        when(seat.isAvailable()).thenReturn(true);
        when(seats.saveAndFlush(seat)).thenThrow(new ObjectOptimisticLockingFailureException(Seat.class, 1L));

        assertThatThrownBy(() -> new OptimisticSeatHolder(seats).hold(1L, "bob", UNTIL))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(BookingErrors.SEAT_UNAVAILABLE));
    }

    @Test
    @DisplayName("optimistic: seat already taken when read, no write attempted")
    void optimisticAlreadyTaken() {
        when(seats.findById(1L)).thenReturn(Optional.of(seat));
        when(seat.isAvailable()).thenReturn(false);

        assertThatThrownBy(() -> new OptimisticSeatHolder(seats).hold(1L, "bob", UNTIL))
                .isInstanceOf(ApiException.class);
        verify(seats, never()).saveAndFlush(seat);
    }
}
