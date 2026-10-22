package com.sunny.flashsale.booking;

import com.sunny.flashsale.booking.domain.Booking;
import com.sunny.flashsale.booking.service.BookingErrors;
import com.sunny.flashsale.booking.service.BookingService;
import com.sunny.flashsale.booking.service.LockingStrategy;
import com.sunny.flashsale.common.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The proof that matters: dozens of real threads, a real MySQL, and the invariant "never more live bookings
 * than seats". Mocks cannot show this, because the race lives inside InnoDB's row locking.
 * <p>
 * Needs Docker (Testcontainers starts mysql:8.4); skipped automatically when Docker is not running.
 * Uses a servlet-type context (the default MOCK web environment) because the shared Clock bean from the
 * common module is only registered for web applications.
 */
@SpringBootTest(properties = {
        "spring.flyway.locations=classpath:db/migration",
        "booking.outbox.enabled=false",   // schema only, no demo seed
        "spring.data.redis.password=unused-in-phase-2",
        "spring.datasource.hikari.maximum-pool-size=30",    // 80-120 threads must queue for connections, not time out
        "spring.datasource.hikari.connection-timeout=60000"
})
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class BookingConcurrencyTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4").withDatabaseName("booking_db");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired BookingService bookingService;
    @Autowired JdbcTemplate jdbc;

    // ------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "80 threads fight for ONE seat using {0}: exactly one wins")
    @EnumSource(LockingStrategy.class)
    void manyThreadsOneSeat(LockingStrategy strategy) throws Exception {
        long eventId = createEvent(5);

        Map<String, Integer> outcomes = race(80, n ->
                bookingService.book("user-" + n, eventId, "S-001", UUID.randomUUID().toString(), strategy));

        assertThat(outcomes).containsEntry("OK", 1).containsEntry(BookingErrors.SEAT_UNAVAILABLE, 79).hasSize(2);
        assertThat(liveBookings(eventId)).isEqualTo(1);
        assertThat(seatsWithStatus(eventId, "HELD")).isEqualTo(1);
        assertThat(seatsWithStatus(eventId, "AVAILABLE")).isEqualTo(4);
        assertNoOversell(eventId, 5);
    }

    @Test
    @DisplayName("120 threads ask for ANY seat among 100 (SKIP LOCKED): exactly 100 succeed, 20 are told it is full")
    void manyThreadsAnySeat() throws Exception {
        long eventId = createEvent(100);

        Map<String, Integer> outcomes = race(120, n ->
                bookingService.book("user-" + n, eventId, null, UUID.randomUUID().toString()));

        assertThat(outcomes).containsEntry("OK", 100).containsEntry(BookingErrors.NO_SEAT_AVAILABLE, 20).hasSize(2);
        assertThat(liveBookings(eventId)).isEqualTo(100);
        assertThat(seatsWithStatus(eventId, "HELD")).isEqualTo(100);
        assertThat(seatsWithStatus(eventId, "AVAILABLE")).isZero();
        assertNoOversell(eventId, 100);
    }

    @Test
    @DisplayName("40 threads race confirm against cancel on one booking: booking and seat always end consistent")
    void confirmVersusCancel() throws Exception {
        long eventId = createEvent(1);
        Booking booking = bookingService.book("alice", eventId, "S-001", UUID.randomUUID().toString());

        Map<String, Integer> outcomes = race(40, n -> {
            if (n % 2 == 0) {
                bookingService.confirm(booking.getId());
            } else {
                bookingService.cancel(booking.getId(), "payment failed");
            }
        });

        // Only business conflicts are allowed as failures; any other key would be a crash or deadlock.
        assertThat(outcomes.keySet()).isSubsetOf("OK", BookingErrors.INVALID_BOOKING_STATE);

        String bookingStatus = jdbc.queryForObject("select status from bookings where id = ?", String.class, booking.getId());
        String seatStatus = jdbc.queryForObject("select status from seats where event_id = ?", String.class, eventId);
        boolean confirmedAndBooked = "CONFIRMED".equals(bookingStatus) && "BOOKED".equals(seatStatus);
        boolean cancelledAndFree = "CANCELLED".equals(bookingStatus) && "AVAILABLE".equals(seatStatus);
        assertThat(confirmedAndBooked || cancelledAndFree)
                .as("booking=%s seat=%s", bookingStatus, seatStatus).isTrue();
    }

    @Test
    @DisplayName("database backstop: even with NO application lock, a second live booking for a seat is refused")
    void databaseRefusesDoubleBooking() {
        long eventId = createEvent(1);
        long seatId = jdbc.queryForObject("select id from seats where event_id = ?", Long.class, eventId);

        insertBooking(eventId, seatId, "PENDING_PAYMENT", "u1", "k1");

        assertThatThrownBy(() -> insertBooking(eventId, seatId, "CONFIRMED", "u2", "k2"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_booking_active_seat");

        // A dead booking frees the seat for a new live one (the generated column is NULL for dead bookings).
        jdbc.update("update bookings set status = 'CANCELLED' where seat_id = ?", seatId);
        insertBooking(eventId, seatId, "PENDING_PAYMENT", "u3", "k3");
        assertThat(liveBookings(eventId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------ helpers

    /** Releases all threads at the same instant and returns outcome -> count ("OK", an ApiException code, or "ERROR ..."). */
    private Map<String, Integer> race(int threads, IntConsumer attempt) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                int n = i;
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        attempt.accept(n);
                        return "OK";
                    } catch (ApiException e) {
                        return e.getCode();
                    } catch (Exception e) {
                        return "ERROR " + e;
                    }
                }));
            }
            ready.await();
            go.countDown();
            Map<String, Integer> counts = new TreeMap<>();
            for (Future<String> f : results) {
                counts.merge(f.get(120, TimeUnit.SECONDS), 1, Integer::sum);
            }
            return counts;
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

    private void insertBooking(long eventId, long seatId, String status, String userId, String key) {
        jdbc.update("insert into bookings (id, user_id, event_id, seat_id, status, amount, idempotency_key) "
                + "values (?, ?, ?, ?, ?, 100.00, ?)", UUID.randomUUID().toString(), userId, eventId, seatId, status, key);
    }

    private long liveBookings(long eventId) {
        return jdbc.queryForObject(
                "select count(*) from bookings where event_id = ? and status in ('PENDING_PAYMENT','CONFIRMED')", Long.class, eventId);
    }

    private long seatsWithStatus(long eventId, String status) {
        return jdbc.queryForObject("select count(*) from seats where event_id = ? and status = ?", Long.class, eventId, status);
    }

    /** The headline invariant: live bookings never exceed seats, and no seat has two of them. */
    private void assertNoOversell(long eventId, int totalSeats) {
        long live = liveBookings(eventId);
        long distinctSeats = jdbc.queryForObject(
                "select count(distinct seat_id) from bookings where event_id = ? and status in ('PENDING_PAYMENT','CONFIRMED')",
                Long.class, eventId);
        assertThat(live).isLessThanOrEqualTo(totalSeats);
        assertThat(distinctSeats).isEqualTo(live);
    }
}
