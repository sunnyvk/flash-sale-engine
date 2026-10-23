package com.sunny.flashsale.booking.saga;

import com.sunny.flashsale.booking.config.BookingProperties;
import com.sunny.flashsale.booking.metrics.BookingMetrics;
import com.sunny.flashsale.booking.service.BookingErrors;
import com.sunny.flashsale.booking.service.BookingService;
import com.sunny.flashsale.booking.service.LockingStrategy;
import com.sunny.flashsale.common.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PendingBookingReaperTest {

    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");

    @Mock JdbcTemplate jdbc;
    @Mock BookingService bookings;

    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    private PendingBookingReaper reaper() {
        BookingProperties props = new BookingProperties(Duration.ofMinutes(5), LockingStrategy.ATOMIC_UPDATE);
        return new PendingBookingReaper(jdbc, bookings, props, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30), 100, new BookingMetrics(registry));
    }

    @Test
    @DisplayName("looks for bookings older than hold-ttl + grace (5m30s) and cancels each with reason 'payment timeout'")
    void cancelsStaleBookings() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 10, 18, 9, 54, 30);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(cutoff), eq(100))).thenReturn(List.of("b-1", "b-2"));

        assertThat(reaper().reapOnce()).isEqualTo(2);
        assertThat(registry.counter("flashsale.reaper.cancelled").count()).isEqualTo(2.0);

        verify(bookings).cancel("b-1", "payment timeout");
        verify(bookings).cancel("b-2", "payment timeout");
    }

    @Test
    @DisplayName("a booking that was paid or cancelled in the meantime is skipped, and the rest are still processed")
    void skipsBookingsThatChangedState() {
        when(jdbc.queryForList(anyString(), eq(String.class), org.mockito.ArgumentMatchers.any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of("b-1", "b-2"));
        doThrow(ApiException.conflict(BookingErrors.INVALID_BOOKING_STATE, "no longer pending")).when(bookings).cancel("b-1", "payment timeout");

        assertThat(reaper().reapOnce()).isEqualTo(1);

        verify(bookings).cancel("b-2", "payment timeout");
    }

    @Test
    @DisplayName("nothing stale: nothing cancelled")
    void nothingStale() {
        when(jdbc.queryForList(anyString(), eq(String.class), org.mockito.ArgumentMatchers.any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of());

        assertThat(reaper().reapOnce()).isZero();

        verify(bookings, never()).cancel(anyString(), anyString());
    }

    @Test
    @DisplayName("run() swallows database errors so the schedule keeps going")
    void runSurvivesErrors() {
        when(jdbc.queryForList(anyString(), eq(String.class), org.mockito.ArgumentMatchers.any(LocalDateTime.class), eq(100)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));

        reaper().run();   // must not throw
    }
}
