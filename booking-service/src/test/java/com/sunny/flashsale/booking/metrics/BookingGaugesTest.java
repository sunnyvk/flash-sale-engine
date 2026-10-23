package com.sunny.flashsale.booking.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingGaugesTest {

    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");

    @Mock JdbcTemplate jdbc;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);

    /** A clock whose time the test moves, to exercise the 5-second cache. */
    private final Clock movable = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    private BookingGauges gauges;

    @BeforeEach
    void setUp() {
        gauges = new BookingGauges(jdbc, movable);
        gauges.bindTo(registry);
    }

    private void databaseHolds(long available, long held, long booked, long pending, LocalDateTime oldest) {
        when(jdbc.queryForList("select status, count(*) as c from seats group by status")).thenReturn(List.of(
                Map.of("status", "AVAILABLE", "c", available), Map.of("status", "HELD", "c", held), Map.of("status", "BOOKED", "c", booked)));
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(pending);
        when(jdbc.queryForList(anyString(), eq(LocalDateTime.class))).thenReturn(oldest == null ? List.of() : List.of(oldest));
    }

    private double seats(String status) {
        return registry.get("flashsale.seats").tag("status", status).gauge().value();
    }

    @Test
    @DisplayName("seat counts and outbox backlog come straight from the database")
    void readsValues() {
        databaseHolds(70, 10, 20, 3, LocalDateTime.of(2026, 10, 22, 9, 59, 30));

        assertThat(seats("AVAILABLE")).isEqualTo(70);
        assertThat(seats("HELD")).isEqualTo(10);
        assertThat(seats("BOOKED")).isEqualTo(20);
        assertThat(registry.get("flashsale.outbox.pending").gauge().value()).isEqualTo(3);
        assertThat(registry.get("flashsale.outbox.oldest.pending.age").gauge().value()).isEqualTo(30);
    }

    @Test
    @DisplayName("empty outbox: backlog 0 and age 0 (not unknown)")
    void emptyOutbox() {
        databaseHolds(100, 0, 0, 0, null);

        assertThat(registry.get("flashsale.outbox.pending").gauge().value()).isZero();
        assertThat(registry.get("flashsale.outbox.oldest.pending.age").gauge().value()).isZero();
    }

    @Test
    @DisplayName("a status with no rows reports 0")
    void missingStatusIsZero() {
        when(jdbc.queryForList("select status, count(*) as c from seats group by status"))
                .thenReturn(List.of(Map.of("status", "AVAILABLE", "c", 5L)));
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
        when(jdbc.queryForList(anyString(), eq(LocalDateTime.class))).thenReturn(List.of());

        assertThat(seats("BOOKED")).isZero();
        assertThat(seats("AVAILABLE")).isEqualTo(5);
    }

    @Test
    @DisplayName("scraping all gauges within 5 s runs the queries once; after 5 s it refreshes")
    void cachesBetweenScrapes() {
        databaseHolds(70, 10, 20, 3, null);

        seats("AVAILABLE");
        seats("HELD");
        registry.get("flashsale.outbox.pending").gauge().value();
        verify(jdbc, times(1)).queryForList("select status, count(*) as c from seats group by status");

        now.set(NOW.plusSeconds(6));
        seats("AVAILABLE");
        verify(jdbc, times(2)).queryForList("select status, count(*) as c from seats group by status");
    }

    @Test
    @DisplayName("database down: gauges are NaN and the scrape does not throw")
    void databaseDownIsNaN() {
        when(jdbc.queryForList("select status, count(*) as c from seats group by status"))
                .thenThrow(new DataAccessResourceFailureException("down"));

        assertThat(seats("HELD")).isNaN();
        assertThat(registry.get("flashsale.outbox.pending").gauge().value()).isNaN();
        verify(jdbc, never()).queryForObject(anyString(), eq(Long.class));
    }
}
