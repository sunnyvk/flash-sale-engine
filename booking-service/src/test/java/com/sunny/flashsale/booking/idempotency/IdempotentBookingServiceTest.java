package com.sunny.flashsale.booking.idempotency;

import com.sunny.flashsale.booking.domain.Booking;
import com.sunny.flashsale.booking.idempotency.IdempotencyStore.Acquired;
import com.sunny.flashsale.booking.idempotency.IdempotencyStore.Bypass;
import com.sunny.flashsale.booking.idempotency.IdempotencyStore.InProgress;
import com.sunny.flashsale.booking.idempotency.IdempotencyStore.Mismatch;
import com.sunny.flashsale.booking.idempotency.IdempotencyStore.Replay;
import com.sunny.flashsale.booking.service.BookingErrors;
import com.sunny.flashsale.booking.service.BookingService;
import com.sunny.flashsale.common.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotentBookingServiceTest {

    private static final String USER = "alice";
    private static final String KEY = "key-1";
    private static final long EVENT = 1L;
    private static final String SEAT = "A-001";
    private static final String FP = IdempotentBookingService.fingerprint(EVENT, SEAT);

    @Mock BookingService bookingService;
    @Mock IdempotencyStore store;

    private IdempotentBookingService service;
    private final Booking booking = new Booking("b-1", USER, EVENT, 42L, new BigDecimal("1500.00"), KEY);

    @BeforeEach
    void setUp() {
        service = new IdempotentBookingService(bookingService, store);
    }

    @Test
    @DisplayName("new key: books once, records the result, reports replayed=false")
    void firstRequest() {
        when(store.begin(USER, KEY, FP)).thenReturn(new Acquired("lock"));
        when(bookingService.book(USER, EVENT, SEAT, KEY)).thenReturn(booking);

        BookingResult result = service.book(USER, EVENT, SEAT, KEY);

        assertThat(result.replayed()).isFalse();
        assertThat(result.booking()).isSameAs(booking);
        verify(store).complete(USER, KEY, FP, "b-1");
    }

    @Test
    @DisplayName("completed key: answers from the stored booking and never touches the seat")
    void replayFromRedis() {
        when(store.begin(USER, KEY, FP)).thenReturn(new Replay("b-1"));
        when(bookingService.get("b-1")).thenReturn(booking);

        BookingResult result = service.book(USER, EVENT, SEAT, KEY);

        assertThat(result.replayed()).isTrue();
        verify(bookingService, never()).book(anyString(), eq(EVENT), any(), anyString());
    }

    @Test
    @DisplayName("key still in progress: 409 REQUEST_IN_PROGRESS, nothing booked")
    void inProgress() {
        when(store.begin(USER, KEY, FP)).thenReturn(new InProgress());

        assertThatThrownBy(() -> service.book(USER, EVENT, SEAT, KEY))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(BookingErrors.REQUEST_IN_PROGRESS);
                    assertThat(e.getStatus().value()).isEqualTo(409);
                });
        verifyNoInteractions(bookingService);
    }

    @Test
    @DisplayName("key reused for a different request: 422 IDEMPOTENCY_KEY_REUSED")
    void mismatch() {
        when(store.begin(USER, KEY, FP)).thenReturn(new Mismatch());

        assertThatThrownBy(() -> service.book(USER, EVENT, SEAT, KEY))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(BookingErrors.IDEMPOTENCY_KEY_REUSED);
                    assertThat(e.getStatus().value()).isEqualTo(422);
                });
        verifyNoInteractions(bookingService);
    }

    @Test
    @DisplayName("booking fails (seat taken): the key is released so the client can retry, and nothing is recorded")
    void failureReleasesKey() {
        when(store.begin(USER, KEY, FP)).thenReturn(new Acquired("lock"));
        when(bookingService.book(USER, EVENT, SEAT, KEY)).thenThrow(BookingErrors.seatUnavailable());

        assertThatThrownBy(() -> service.book(USER, EVENT, SEAT, KEY))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(BookingErrors.SEAT_UNAVAILABLE));
        verify(store).abort(USER, KEY, "lock");
        verify(store, never()).complete(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("an unexpected crash also releases the key")
    void crashReleasesKey() {
        when(store.begin(USER, KEY, FP)).thenReturn(new Acquired("lock"));
        when(bookingService.book(USER, EVENT, SEAT, KEY)).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> service.book(USER, EVENT, SEAT, KEY)).isInstanceOf(IllegalStateException.class);
        verify(store).abort(USER, KEY, "lock");
    }

    @Test
    @DisplayName("Redis forgot the key but MySQL has it: duplicate insert is answered with the existing booking")
    void databaseFallbackAfterRedisForgot() {
        when(store.begin(USER, KEY, FP)).thenReturn(new Acquired("lock"));
        when(bookingService.book(USER, EVENT, SEAT, KEY))
                .thenThrow(com.sunny.flashsale.common.error.ApiException.conflict(BookingErrors.DUPLICATE_REQUEST, "dup"));
        when(bookingService.findExisting(USER, KEY, EVENT, SEAT)).thenReturn(Optional.of(booking));

        BookingResult result = service.book(USER, EVENT, SEAT, KEY);

        assertThat(result.replayed()).isTrue();
        assertThat(result.booking()).isSameAs(booking);
        verify(store).complete(USER, KEY, FP, "b-1");
    }

    @Test
    @DisplayName("Redis forgot the key and the retry wants the seat we still hold: SEAT_UNAVAILABLE is answered with the existing booking")
    void seatUnavailableButKeyAlreadyBooked() {
        when(store.begin(USER, KEY, FP)).thenReturn(new Acquired("lock"));
        when(bookingService.book(USER, EVENT, SEAT, KEY)).thenThrow(BookingErrors.seatUnavailable());
        when(bookingService.findExisting(USER, KEY, EVENT, SEAT)).thenReturn(Optional.of(booking));

        BookingResult result = service.book(USER, EVENT, SEAT, KEY);

        assertThat(result.replayed()).isTrue();
        assertThat(result.booking()).isSameAs(booking);
        verify(store).complete(USER, KEY, FP, "b-1");
    }

    @Test
    @DisplayName("SEAT_UNAVAILABLE for a key that never booked anything is a genuine lost race and is rethrown")
    void seatUnavailableForNewKeyIsRethrown() {
        when(store.begin(USER, KEY, FP)).thenReturn(new Acquired("lock"));
        when(bookingService.book(USER, EVENT, SEAT, KEY)).thenThrow(BookingErrors.seatUnavailable());
        when(bookingService.findExisting(USER, KEY, EVENT, SEAT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.book(USER, EVENT, SEAT, KEY))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(BookingErrors.SEAT_UNAVAILABLE));
        verify(store).abort(USER, KEY, "lock");
    }

    @Test
    @DisplayName("Redis down (Bypass): still books, protected by the database constraint alone")
    void bypassBooks() {
        when(store.begin(USER, KEY, FP)).thenReturn(new Bypass());
        when(bookingService.book(USER, EVENT, SEAT, KEY)).thenReturn(booking);

        BookingResult result = service.book(USER, EVENT, SEAT, KEY);

        assertThat(result.replayed()).isFalse();
        verify(store, never()).complete(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Redis down and a duplicate key: replayed from MySQL")
    void bypassReplaysFromDatabase() {
        when(store.begin(USER, KEY, FP)).thenReturn(new Bypass());
        when(bookingService.book(USER, EVENT, SEAT, KEY))
                .thenThrow(ApiException.conflict(BookingErrors.DUPLICATE_REQUEST, "dup"));
        when(bookingService.findExisting(USER, KEY, EVENT, SEAT)).thenReturn(Optional.of(booking));

        assertThat(service.book(USER, EVENT, SEAT, KEY).replayed()).isTrue();
    }

    @Test
    @DisplayName("a malformed key is rejected before Redis is ever called")
    void invalidKeyNeverReachesRedis() {
        assertThatThrownBy(() -> service.book(USER, EVENT, SEAT, "bad key\n"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(BookingErrors.INVALID_IDEMPOTENCY_KEY));
        verifyNoInteractions(store, bookingService);
    }

    @Test
    @DisplayName("fingerprint: same request hashes the same, any difference changes it, seat case is ignored")
    void fingerprintRules() {
        assertThat(IdempotentBookingService.fingerprint(1, "A-001")).isEqualTo(IdempotentBookingService.fingerprint(1, " a-001 "));
        assertThat(IdempotentBookingService.fingerprint(1, "A-001")).isNotEqualTo(IdempotentBookingService.fingerprint(2, "A-001"));
        assertThat(IdempotentBookingService.fingerprint(1, "A-001")).isNotEqualTo(IdempotentBookingService.fingerprint(1, "A-002"));
        assertThat(IdempotentBookingService.fingerprint(1, null)).isEqualTo(IdempotentBookingService.fingerprint(1, "  "));
        assertThat(IdempotentBookingService.fingerprint(1, null)).hasSize(64);
    }
}
