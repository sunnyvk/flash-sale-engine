package com.sunny.flashsale.booking;

import com.sunny.flashsale.booking.idempotency.BookingResult;
import com.sunny.flashsale.booking.idempotency.IdempotentBookingService;
import com.sunny.flashsale.booking.service.BookingErrors;
import com.sunny.flashsale.common.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Idempotency against the real thing: MySQL and Redis in containers, many threads retrying the same request.
 * Skipped automatically when Docker is not running.
 */
@SpringBootTest(properties = {
        "spring.flyway.locations=classpath:db/migration",
        "booking.outbox.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=30",
        "spring.datasource.hikari.connection-timeout=60000"
})
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class IdempotencyIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4").withDatabaseName("booking_db");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", "testpass");

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "testpass");
    }

    @Autowired IdempotentBookingService service;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("50 threads retry the SAME request at once: exactly one booking exists, everyone who succeeds sees it")
    void fiftyRetriesCreateOneBooking() throws Exception {
        long eventId = createEvent(5);
        String key = UUID.randomUUID().toString();

        List<String> outcomes = race(50, n -> service.book("alice", eventId, "S-001", key).booking().getId());

        Set<String> bookingIds = new TreeSet<>();
        long inProgress = 0;
        for (String outcome : outcomes) {
            if (outcome.startsWith("OK:")) {
                bookingIds.add(outcome.substring(3));
            } else if (outcome.equals(BookingErrors.REQUEST_IN_PROGRESS)) {
                inProgress++;
            } else {
                throw new AssertionError("Unexpected outcome: " + outcome);
            }
        }
        assertThat(bookingIds).as("all successful responses carry the same booking").hasSize(1);
        assertThat(outcomes.size() - inProgress).as("at least the winner succeeded").isGreaterThanOrEqualTo(1);
        assertThat(liveBookings(eventId)).isEqualTo(1);
        assertThat(seatsWithStatus(eventId, "HELD")).isEqualTo(1);
    }

    @Test
    @DisplayName("retry after completion is a replay and never touches the seat again")
    void sequentialRetryIsReplay() {
        long eventId = createEvent(3);
        String key = UUID.randomUUID().toString();

        BookingResult first = service.book("alice", eventId, "S-001", key);
        BookingResult second = service.book("alice", eventId, "S-001", key);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.booking().getId()).isEqualTo(first.booking().getId());
        assertThat(liveBookings(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("same key for a different seat is rejected, and the second seat stays free")
    void sameKeyDifferentRequest() {
        long eventId = createEvent(3);
        String key = UUID.randomUUID().toString();
        service.book("alice", eventId, "S-001", key);

        assertThatThrownBy(() -> service.book("alice", eventId, "S-002", key))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(BookingErrors.IDEMPOTENCY_KEY_REUSED));
        assertThat(seatsWithStatus(eventId, "AVAILABLE")).isEqualTo(2);
    }

    @Test
    @DisplayName("Redis loses everything: the retry is still answered from MySQL, no second booking")
    void redisFlushedStillReplays() {
        long eventId = createEvent(3);
        String key = UUID.randomUUID().toString();
        BookingResult first = service.book("alice", eventId, "S-001", key);

        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });

        BookingResult retry = service.book("alice", eventId, "S-001", key);
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.booking().getId()).isEqualTo(first.booking().getId());
        assertThat(liveBookings(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a failed booking releases its key: the retry fails honestly, it is not stuck as in-progress")
    void failedRequestCanBeRetried() {
        long eventId = createEvent(1);
        service.book("alice", eventId, "S-001", "alice-key");
        String bobKey = UUID.randomUUID().toString();

        assertThatThrownBy(() -> service.book("bob", eventId, "S-001", bobKey))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(BookingErrors.SEAT_UNAVAILABLE));
        // Bob's key must not be stuck "in progress": the same call fails the same honest way, not with REQUEST_IN_PROGRESS.
        assertThatThrownBy(() -> service.book("bob", eventId, "S-001", bobKey))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(BookingErrors.SEAT_UNAVAILABLE));
    }

    @Test
    @DisplayName("the same key from two different users is two independent requests")
    void keysAreScopedPerUser() {
        long eventId = createEvent(3);
        String key = UUID.randomUUID().toString();

        BookingResult alice = service.book("alice", eventId, "S-001", key);
        BookingResult bob = service.book("bob", eventId, "S-002", key);

        assertThat(alice.booking().getId()).isNotEqualTo(bob.booking().getId());
        assertThat(liveBookings(eventId)).isEqualTo(2);
    }

    // ------------------------------------------------------------------------------------------ helpers

    private interface Attempt {
        String run(int n);
    }

    /** Releases all threads together; returns "OK:{bookingId}" or the ApiException code for each. */
    private List<String> race(int threads, Attempt attempt) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                int n = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return "OK:" + attempt.run(n);
                    } catch (ApiException e) {
                        return e.getCode();
                    } catch (Exception e) {
                        return "ERROR " + e;
                    }
                }));
            }
            ready.await();
            go.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> f : futures) {
                results.add(f.get(120, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private long createEvent(int seatCount) {
        String name = "test-" + UUID.randomUUID();
        jdbc.update("insert into flash_events (name, starts_at, total_seats, price) values (?, utc_timestamp(3), ?, 100.00)",
                name, seatCount);
        long eventId = jdbc.queryForObject("select id from flash_events where name = ?", Long.class, name);
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= seatCount; i++) {
            rows.add(new Object[]{eventId, String.format("S-%03d", i)});
        }
        jdbc.batchUpdate("insert into seats (event_id, seat_number, status, version) values (?, ?, 'AVAILABLE', 0)", rows);
        return eventId;
    }

    private long liveBookings(long eventId) {
        return jdbc.queryForObject(
                "select count(*) from bookings where event_id = ? and status in ('PENDING_PAYMENT','CONFIRMED')", Long.class, eventId);
    }

    private long seatsWithStatus(long eventId, String status) {
        return jdbc.queryForObject("select count(*) from seats where event_id = ? and status = ?", Long.class, eventId, status);
    }
}
