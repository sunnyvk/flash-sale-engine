package com.sunny.flashsale.booking.saga;

import com.sunny.flashsale.booking.config.BookingProperties;
import com.sunny.flashsale.booking.service.BookingService;
import com.sunny.flashsale.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Saga timeout: if no payment outcome arrives within the hold time (plus a grace period), cancel the booking so the
 * seat goes back on sale. Without this a lost or dead-lettered payment event would keep a seat held forever.
 * Each booking is cancelled in its own transaction, and cancel() is the same compensating step the payment-failure
 * path uses. If a payment succeeds at the same moment, the seat arbiter in confirm()/cancel() lets exactly one win.
 */
@Component
@ConditionalOnProperty(prefix = "booking.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PendingBookingReaper {

    private static final Logger log = LoggerFactory.getLogger(PendingBookingReaper.class);

    private final JdbcTemplate jdbc;
    private final BookingService bookings;
    private final BookingProperties properties;
    private final Clock clock;
    private final Duration grace;
    private final int batchSize;

    public PendingBookingReaper(JdbcTemplate jdbc, BookingService bookings, BookingProperties properties, Clock clock,
                                @Value("${booking.reaper.grace:PT30S}") Duration grace,
                                @Value("${booking.reaper.batch-size:100}") int batchSize) {
        this.jdbc = jdbc;
        this.bookings = bookings;
        this.properties = properties;
        this.clock = clock;
        this.grace = grace;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${booking.reaper.interval:PT30S}", initialDelayString = "${booking.reaper.interval:PT30S}")
    public void run() {
        try {
            reapOnce();
        } catch (RuntimeException e) {
            log.warn("Reaper pass failed, will retry: {}", e.toString());
        }
    }

    /** @return how many bookings were cancelled in this pass */
    public int reapOnce() {
        LocalDateTime cutoff = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .minus(properties.holdTtl()).minus(grace);
        List<String> stale = jdbc.queryForList(
                "select id from bookings where status = 'PENDING_PAYMENT' and created_at < ? order by created_at limit ?",
                String.class, cutoff, batchSize);
        int cancelled = 0;
        for (String id : stale) {
            try {
                bookings.cancel(id, "payment timeout");
                cancelled++;
            } catch (ApiException e) {
                log.info("Reaper skipped booking, it changed state meanwhile: bookingId={} reason={}", id, e.getMessage());
            }
        }
        if (cancelled > 0) {
            log.warn("Reaper cancelled {} booking(s) with no payment outcome", cancelled);
        }
        return cancelled;
    }
}
