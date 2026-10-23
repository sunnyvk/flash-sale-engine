package com.sunny.flashsale.booking.service;

import com.sunny.flashsale.booking.config.BookingProperties;
import com.sunny.flashsale.booking.domain.Booking;
import com.sunny.flashsale.booking.domain.BookingStatus;
import com.sunny.flashsale.booking.domain.FlashEvent;
import com.sunny.flashsale.booking.domain.Seat;
import com.sunny.flashsale.booking.domain.SeatStatus;
import com.sunny.flashsale.booking.metrics.BookingMetrics;
import com.sunny.flashsale.booking.outbox.OutboxWriter;
import com.sunny.flashsale.booking.repository.BookingRepository;
import com.sunny.flashsale.booking.repository.FlashEventRepository;
import com.sunny.flashsale.booking.repository.SeatRepository;
import com.sunny.flashsale.booking.repository.SeatStatusCount;
import com.sunny.flashsale.common.error.ApiException;
import com.sunny.flashsale.common.events.BookingEventType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    private static final long EVENT_ID = 1L;
    private static final long SEAT_ID = 42L;
    private static final String USER = "alice";
    private static final String KEY = "key-1";
    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

    @Mock FlashEventRepository events;
    @Mock SeatRepository seats;
    @Mock BookingRepository bookings;
    @Mock SeatHolder atomicHolder;
    @Mock SeatHolder pessimisticHolder;
    @Mock FlashEvent event;
    @Mock OutboxWriter outbox;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private BookingService service;

    @BeforeEach
    void setUp() {
        lenient().when(atomicHolder.strategy()).thenReturn(LockingStrategy.ATOMIC_UPDATE);
        lenient().when(pessimisticHolder.strategy()).thenReturn(LockingStrategy.PESSIMISTIC);
        lenient().when(event.getPrice()).thenReturn(new BigDecimal("1500.00"));
        lenient().when(events.findById(EVENT_ID)).thenReturn(Optional.of(event));
        lenient().when(seats.findIdByEventIdAndSeatNumber(EVENT_ID, "A-001")).thenReturn(Optional.of(SEAT_ID));
        lenient().when(bookings.saveAndFlush(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        Clock fixed = Clock.fixed(NOW, ZoneOffset.UTC);
        BookingProperties props = new BookingProperties(Duration.ofMinutes(5), LockingStrategy.ATOMIC_UPDATE);
        service = new BookingService(events, seats, bookings, List.of(atomicHolder, pessimisticHolder), props, fixed, outbox,
                new BookingMetrics(registry));
    }

    // ------------------------------------------------------------------ book

    @Test
    @DisplayName("books a specific seat: holds it until now + TTL, then saves a PENDING_PAYMENT booking at the event price")
    void booksSpecificSeat() {
        Booking result = service.book(USER, EVENT_ID, "A-001", KEY);

        LocalDateTime expectedUntil = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).plusMinutes(5);
        verify(atomicHolder).hold(SEAT_ID, USER, expectedUntil);
        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        verify(bookings).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(saved.getValue().getAmount()).isEqualByComparingTo("1500.00");
        assertThat(saved.getValue().getSeatId()).isEqualTo(SEAT_ID);
        assertThat(saved.getValue().getIdempotencyKey()).isEqualTo(KEY);
        assertThat(result.getId()).hasSize(36);
    }

    @Test
    @DisplayName("an explicit strategy overrides the configured one")
    void explicitStrategyIsUsed() {
        service.book(USER, EVENT_ID, "A-001", KEY, LockingStrategy.PESSIMISTIC);

        verify(pessimisticHolder).hold(eq(SEAT_ID), eq(USER), any(LocalDateTime.class));
        verify(atomicHolder, never()).hold(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("losing the seat race: no booking is saved")
    void seatUnavailableSavesNothing() {
        org.mockito.Mockito.doThrow(BookingErrors.seatUnavailable()).when(atomicHolder).hold(anyLong(), anyString(), any());

        assertThatThrownBy(() -> service.book(USER, EVENT_ID, "A-001", KEY))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(BookingErrors.SEAT_UNAVAILABLE));
        verify(bookings, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("unknown event is a 404 before any seat is touched")
    void unknownEvent() {
        when(events.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.book(USER, 99L, "A-001", KEY))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(BookingErrors.EVENT_NOT_FOUND));
        verifyNoInteractions(seats);
    }

    @Test
    @DisplayName("unknown seat number is a 404")
    void unknownSeat() {
        when(seats.findIdByEventIdAndSeatNumber(EVENT_ID, "Z-999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.book(USER, EVENT_ID, "Z-999", KEY))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(BookingErrors.SEAT_NOT_FOUND));
    }

    @Test
    @DisplayName("rejects malformed idempotency keys before touching the database")
    void invalidIdempotencyKey() {
        for (String bad : new String[]{null, "", "has space", "line\nbreak", "x".repeat(65)}) {
            assertThatThrownBy(() -> service.book(USER, EVENT_ID, "A-001", bad))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getCode()).isEqualTo(BookingErrors.INVALID_IDEMPOTENCY_KEY));
        }
        verifyNoInteractions(events, seats, bookings);
    }

    @Test
    @DisplayName("no seat number: takes the next seat via SKIP LOCKED and holds it")
    void booksAnySeat() {
        Seat seat = org.mockito.Mockito.mock(Seat.class);
        when(seat.getId()).thenReturn(7L);
        when(seats.lockNextAvailable(EVENT_ID)).thenReturn(Optional.of(seat));

        Booking result = service.book(USER, EVENT_ID, null, KEY);

        verify(seat).hold(eq(USER), any(LocalDateTime.class));
        assertThat(result.getSeatId()).isEqualTo(7L);
        verify(atomicHolder, never()).hold(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("no seat number and nothing available: NO_SEAT_AVAILABLE")
    void soldOut() {
        when(seats.lockNextAvailable(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.book(USER, EVENT_ID, "  ", KEY))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(BookingErrors.NO_SEAT_AVAILABLE));
        verify(bookings, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("DB backstop uq_booking_active_seat is reported as SEAT_UNAVAILABLE")
    void activeSeatIndexViolation() {
        when(bookings.saveAndFlush(any(Booking.class))).thenThrow(duplicate("bookings.uq_booking_active_seat"));

        assertThatThrownBy(() -> service.book(USER, EVENT_ID, "A-001", KEY))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(BookingErrors.SEAT_UNAVAILABLE));
    }

    @Test
    @DisplayName("DB constraint uq_booking_user_idempotency is reported as DUPLICATE_REQUEST")
    void idempotencyIndexViolation() {
        when(bookings.saveAndFlush(any(Booking.class))).thenThrow(duplicate("bookings.uq_booking_user_idempotency"));

        assertThatThrownBy(() -> service.book(USER, EVENT_ID, "A-001", KEY))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(BookingErrors.DUPLICATE_REQUEST));
    }

    @Test
    @DisplayName("an unrelated integrity violation is not disguised as a business error")
    void unknownIntegrityViolationPropagates() {
        DataIntegrityViolationException other = duplicate("bookings.PRIMARY");
        when(bookings.saveAndFlush(any(Booking.class))).thenThrow(other);

        assertThatThrownBy(() -> service.book(USER, EVENT_ID, "A-001", KEY)).isSameAs(other);
    }

    // --------------------------------------------------------------- confirm

    @Test
    @DisplayName("confirm: seat HELD -> BOOKED and booking CONFIRMED")
    void confirmHappyPath() {
        Booking booking = pending();
        when(bookings.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(seats.markBooked(SEAT_ID)).thenReturn(1);

        Booking result = service.confirm(booking.getId());

        assertThat(result.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    @DisplayName("confirm twice is harmless and does not touch the seat again")
    void confirmIsIdempotent() {
        Booking booking = pending();
        booking.markConfirmed();
        when(bookings.findById(booking.getId())).thenReturn(Optional.of(booking));

        assertThat(service.confirm(booking.getId()).getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        verify(seats, never()).markBooked(anyLong());
    }

    @Test
    @DisplayName("confirm when the hold is gone (expired/released): conflict, booking stays PENDING_PAYMENT")
    void confirmAfterSeatReleased() {
        Booking booking = pending();
        when(bookings.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(seats.markBooked(SEAT_ID)).thenReturn(0);

        assertThatThrownBy(() -> service.confirm(booking.getId()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(BookingErrors.INVALID_BOOKING_STATE));
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    @Test
    @DisplayName("confirming a cancelled booking is a conflict")
    void confirmCancelled() {
        Booking booking = pending();
        booking.markCancelled("payment failed");
        when(bookings.findById(booking.getId())).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.confirm(booking.getId()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(BookingErrors.INVALID_BOOKING_STATE));
        verify(seats, never()).markBooked(anyLong());
    }

    // ---------------------------------------------------------------- cancel

    @Test
    @DisplayName("compensation: cancel releases the seat and records the reason")
    void cancelHappyPath() {
        Booking booking = pending();
        when(bookings.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(seats.release(SEAT_ID)).thenReturn(1);

        Booking result = service.cancel(booking.getId(), "payment failed");

        assertThat(result.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(result.getFailureReason()).isEqualTo("payment failed");
        verify(seats).release(SEAT_ID);
    }

    @Test
    @DisplayName("compensation is idempotent: cancelling an already cancelled booking releases nothing")
    void cancelTwice() {
        Booking booking = pending();
        booking.markCancelled("first");
        when(bookings.findById(booking.getId())).thenReturn(Optional.of(booking));

        Booking result = service.cancel(booking.getId(), "second");

        assertThat(result.getFailureReason()).isEqualTo("first");
        verify(seats, never()).release(anyLong());
    }

    @Test
    @DisplayName("a confirmed booking cannot be cancelled by compensation")
    void cancelConfirmed() {
        Booking booking = pending();
        booking.markConfirmed();
        when(bookings.findById(booking.getId())).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.cancel(booking.getId(), "late"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(BookingErrors.INVALID_BOOKING_STATE));
        verify(seats, never()).release(anyLong());
    }

    @Test
    @DisplayName("cancel that lost a race to confirm (seat no longer HELD) fails and must not overwrite the booking")
    void cancelLosesRaceToConfirm() {
        Booking booking = pending();
        when(bookings.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(seats.release(SEAT_ID)).thenReturn(0);

        assertThatThrownBy(() -> service.cancel(booking.getId(), "late"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(BookingErrors.INVALID_BOOKING_STATE));
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    @Test
    @DisplayName("unknown booking id is a 404")
    void bookingNotFound() {
        when(bookings.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("nope"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(BookingErrors.BOOKING_NOT_FOUND));
    }

    // ---------------------------------------------------------- availability

    @Test
    @DisplayName("availability fills in zero for statuses with no rows")
    void availability() {
        when(events.existsById(EVENT_ID)).thenReturn(true);
        when(seats.countByStatus(EVENT_ID)).thenReturn(List.of(
                new SeatStatusCount(SeatStatus.AVAILABLE, 90), new SeatStatusCount(SeatStatus.HELD, 10)));

        BookingService.Availability result = service.availability(EVENT_ID);

        assertThat(result).isEqualTo(new BookingService.Availability(EVENT_ID, 90, 10, 0));
    }

    // ---------------------------------------------------------- findExisting

    @Test
    @DisplayName("findExisting returns the booking already created with the same key and request")
    void findExistingMatches() {
        Booking existing = pending();
        when(bookings.findByUserIdAndIdempotencyKey(USER, KEY)).thenReturn(Optional.of(existing));

        assertThat(service.findExisting(USER, KEY, EVENT_ID, "A-001")).containsSame(existing);
    }

    @Test
    @DisplayName("findExisting is empty when the key was never used")
    void findExistingNone() {
        when(bookings.findByUserIdAndIdempotencyKey(USER, KEY)).thenReturn(Optional.empty());

        assertThat(service.findExisting(USER, KEY, EVENT_ID, "A-001")).isEmpty();
    }

    @Test
    @DisplayName("findExisting rejects a key reused for a different event")
    void findExistingDifferentEvent() {
        when(bookings.findByUserIdAndIdempotencyKey(USER, KEY)).thenReturn(Optional.of(pending()));

        assertThatThrownBy(() -> service.findExisting(USER, KEY, 2L, "A-001"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(BookingErrors.IDEMPOTENCY_KEY_REUSED);
                    assertThat(e.getStatus().value()).isEqualTo(422);
                });
    }

    @Test
    @DisplayName("findExisting rejects a key reused for a different seat")
    void findExistingDifferentSeat() {
        when(bookings.findByUserIdAndIdempotencyKey(USER, KEY)).thenReturn(Optional.of(pending()));
        when(seats.findIdByEventIdAndSeatNumber(EVENT_ID, "A-002")).thenReturn(Optional.of(99L));

        assertThatThrownBy(() -> service.findExisting(USER, KEY, EVENT_ID, "A-002"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(BookingErrors.IDEMPOTENCY_KEY_REUSED));
    }

    // ---------------------------------------------------------------- outbox

    @Test
    @DisplayName("a successful booking appends BOOKING_HELD to the outbox")
    void bookingAppendsHeldEvent() {
        Booking result = service.book(USER, EVENT_ID, "A-001", KEY);

        verify(outbox).append(BookingEventType.BOOKING_HELD, result, null);
    }

    @Test
    @DisplayName("a lost seat race appends no event")
    void failedBookingAppendsNothing() {
        org.mockito.Mockito.doThrow(BookingErrors.seatUnavailable()).when(atomicHolder).hold(anyLong(), anyString(), any());

        assertThatThrownBy(() -> service.book(USER, EVENT_ID, "A-001", KEY)).isInstanceOf(ApiException.class);

        verifyNoInteractions(outbox);
    }

    @Test
    @DisplayName("confirm appends BOOKING_CONFIRMED")
    void confirmAppendsEvent() {
        Booking booking = pending();
        when(bookings.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(seats.markBooked(SEAT_ID)).thenReturn(1);

        service.confirm(booking.getId());

        verify(outbox).append(BookingEventType.BOOKING_CONFIRMED, booking, null);
    }

    @Test
    @DisplayName("cancel appends BOOKING_CANCELLED with the reason")
    void cancelAppendsEvent() {
        Booking booking = pending();
        when(bookings.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(seats.release(SEAT_ID)).thenReturn(1);

        service.cancel(booking.getId(), "payment failed");

        verify(outbox).append(BookingEventType.BOOKING_CANCELLED, booking, "payment failed");
    }

    @Test
    @DisplayName("repeated confirm, repeated cancel and a failed confirm append nothing")
    void noEventWithoutStateChange() {
        Booking confirmed = pending();
        confirmed.markConfirmed();
        Booking cancelled = pending();
        cancelled.markCancelled("first");
        Booking stuck = new Booking("22222222-2222-2222-2222-222222222222", USER, EVENT_ID, SEAT_ID, new BigDecimal("1500.00"), "k2");
        when(bookings.findById(confirmed.getId())).thenReturn(Optional.of(confirmed));
        when(bookings.findById(stuck.getId())).thenReturn(Optional.of(stuck));
        when(seats.markBooked(SEAT_ID)).thenReturn(0);

        service.confirm(confirmed.getId());
        assertThatThrownBy(() -> service.confirm(stuck.getId())).isInstanceOf(ApiException.class);

        verifyNoInteractions(outbox);
    }

    // --------------------------------------------------------------- helpers

    private static Booking pending() {
        return new Booking("11111111-1111-1111-1111-111111111111", USER, EVENT_ID, SEAT_ID, new BigDecimal("1500.00"), KEY);
    }

    private static DataIntegrityViolationException duplicate(String indexName) {
        return new DataIntegrityViolationException("could not execute statement",
                new SQLIntegrityConstraintViolationException("Duplicate entry '42' for key '" + indexName + "'"));
    }

    // ------------------------------------------------------------------ metrics

    @Test
    @DisplayName("metrics: cancel() counts one 'cancelled' transition, confirm() one 'confirmed'")
    void countsTransitions() {
        Booking toCancel = new Booking("b-1", USER, EVENT_ID, SEAT_ID, new BigDecimal("1500.00"), KEY);
        Booking toConfirm = new Booking("b-2", USER, EVENT_ID, 43L, new BigDecimal("1500.00"), "key-2");
        when(bookings.findById("b-1")).thenReturn(Optional.of(toCancel));
        when(bookings.findById("b-2")).thenReturn(Optional.of(toConfirm));
        when(seats.release(SEAT_ID)).thenReturn(1);
        when(seats.markBooked(43L)).thenReturn(1);

        service.cancel("b-1", "payment failed");
        service.confirm("b-2");

        assertThat(registry.counter("flashsale.booking.transitions", "to", "cancelled").count()).isEqualTo(1.0);
        assertThat(registry.counter("flashsale.booking.transitions", "to", "confirmed").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("metrics: a repeated cancel is a no-op and is not counted twice")
    void repeatedCancelIsNotCounted() {
        Booking cancelled = new Booking("b-1", USER, EVENT_ID, SEAT_ID, new BigDecimal("1500.00"), KEY);
        cancelled.markCancelled("payment failed");
        when(bookings.findById("b-1")).thenReturn(Optional.of(cancelled));

        service.cancel("b-1", "payment failed");

        assertThat(registry.counter("flashsale.booking.transitions", "to", "cancelled").count()).isZero();
    }

    @Test
    @DisplayName("metrics: the DB unique-index backstop is counted (expected to stay at 0 in production)")
    void countsBackstop() {
        when(bookings.saveAndFlush(any(Booking.class))).thenThrow(new DataIntegrityViolationException("x",
                new SQLIntegrityConstraintViolationException("Duplicate entry '42' for key 'bookings.uq_booking_active_seat'")));

        assertThatThrownBy(() -> service.book(USER, EVENT_ID, "A-001", KEY)).isInstanceOf(ApiException.class);

        assertThat(registry.counter("flashsale.db.backstop").count()).isEqualTo(1.0);
    }
}
