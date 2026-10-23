package com.sunny.flashsale.booking.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gauges read from MySQL: seats per status and the outbox backlog. A gauge is a value that goes up AND down, so it
 * cannot be a counter. The answers are cached for a few seconds because Prometheus asks every gauge on every scrape;
 * one snapshot query set per interval is shared by all of them. If MySQL is down the gauges report NaN (Prometheus
 * treats that as "no data") instead of failing the scrape, so the health problem shows up in the health check, not as
 * a second error here.
 */
@Component
public class BookingGauges implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(BookingGauges.class);
    private static final Duration CACHE = Duration.ofSeconds(5);
    private static final List<String> SEAT_STATUSES = List.of("AVAILABLE", "HELD", "BOOKED");

    record Snapshot(Map<String, Double> seats, double outboxPending, double oldestPendingSeconds) {
    }

    private static final Snapshot UNKNOWN = new Snapshot(Map.of(), Double.NaN, Double.NaN);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private volatile Snapshot snapshot = UNKNOWN;
    private volatile long loadedAtMillis = Long.MIN_VALUE / 2;

    public BookingGauges(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (String status : SEAT_STATUSES) {
            Gauge.builder("flashsale.seats", this, g -> g.current().seats().getOrDefault(status, Double.NaN))
                    .description("Seats per status across all events")
                    .tag("status", status)
                    .register(registry);
        }
        Gauge.builder("flashsale.outbox.pending", this, g -> g.current().outboxPending())
                .description("Outbox rows not yet published to Kafka")
                .register(registry);
        Gauge.builder("flashsale.outbox.oldest.pending.age", this, g -> g.current().oldestPendingSeconds())
                .description("Age of the oldest unpublished outbox row")
                .baseUnit("seconds")
                .register(registry);
    }

    Snapshot current() {
        long now = clock.millis();
        if (now - loadedAtMillis >= CACHE.toMillis()) {
            snapshot = load();
            loadedAtMillis = now;
        }
        return snapshot;
    }

    private Snapshot load() {
        try {
            Map<String, Double> seats = new HashMap<>();
            for (Map<String, Object> row : jdbc.queryForList("select status, count(*) as c from seats group by status")) {
                seats.put(String.valueOf(row.get("status")), ((Number) row.get("c")).doubleValue());
            }
            SEAT_STATUSES.forEach(s -> seats.putIfAbsent(s, 0.0));   // a status with zero rows is 0, not "unknown"

            Long pending = jdbc.queryForObject("select count(*) from outbox_events where status = 'PENDING'", Long.class);
            List<LocalDateTime> oldest = jdbc.queryForList(
                    "select created_at from outbox_events where status = 'PENDING' order by id limit 1", LocalDateTime.class);
            double age = 0.0;
            if (!oldest.isEmpty() && oldest.get(0) != null) {
                age = Math.max(0, Duration.between(oldest.get(0), LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).toSeconds());
            }
            return new Snapshot(seats, pending == null ? 0.0 : pending, age);
        } catch (RuntimeException e) {
            log.warn("Could not read gauge values from MySQL: {}", e.toString());
            return UNKNOWN;
        }
    }
}
