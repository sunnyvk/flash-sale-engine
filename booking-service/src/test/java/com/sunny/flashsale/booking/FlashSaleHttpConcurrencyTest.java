package com.sunny.flashsale.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunny.flashsale.booking.security.Role;
import com.sunny.flashsale.booking.security.TokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The flash sale as a client sees it: the full stack (JWT filter, Redis idempotency, locking, MySQL) driven by
 * hundreds of simultaneous HTTP requests. {@code BookingConcurrencyTest} proves the locking at service level;
 * this proves the layers also hold together. Real MySQL and Redis; Kafka off. Skipped without Docker.
 * <p>
 * Requests go through MockMvc (no network), but each one runs on its own thread through the real filter chain.
 */
@SpringBootTest(properties = {
        "spring.flyway.locations=classpath:db/migration",
        "booking.outbox.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=30",     // hundreds of requests must queue for a connection, not fail
        "spring.datasource.hikari.connection-timeout=60000"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class FlashSaleHttpConcurrencyTest {

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

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenService tokens;
    @Autowired ObjectMapper json;

    /** One HTTP call's outcome. */
    private record Reply(int status, String code, String bookingId, boolean replayed) {
    }

    @Test
    @DisplayName("250 users hit POST /bookings at the same instant for 100 seats: exactly 100 get 201, exactly 150 get 409, nothing else")
    void twoHundredFiftyUsersOneHundredSeats() throws Exception {
        long eventId = createEvent(100);

        List<Reply> replies = race(250, n -> book("flash-user-" + n, eventId, UUID.randomUUID().toString()));

        Map<String, Integer> outcomes = tally(replies);
        assertThat(outcomes).containsEntry("201", 100).containsEntry("409 NO_SEAT_AVAILABLE", 150).hasSize(2);
        assertThat(liveBookings(eventId)).isEqualTo(100);
        assertThat(distinctLiveSeats(eventId)).as("no seat sold twice").isEqualTo(100);
        assertThat(seatsWithStatus(eventId, "HELD")).isEqualTo(100);
        assertThat(seatsWithStatus(eventId, "AVAILABLE")).isZero();

        // Public availability endpoint agrees with the database, and the three counts add up to the seat total.
        String body = mvc.perform(get("/api/v1/events/" + eventId + "/availability")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("available").asLong()).isZero();
        assertThat(json.readTree(body).get("held").asLong()).isEqualTo(100);
    }

    @Test
    @DisplayName("retry storm: 60 users each send the SAME request 3 times at once; every user ends with exactly ONE booking")
    void duplicateRequestsCreateOneBookingPerUser() throws Exception {
        long eventId = createEvent(100);          // enough seats for everyone: any failure here is idempotency's fault
        int users = 60;
        List<String> keys = new ArrayList<>();
        for (int u = 0; u < users; u++) {
            keys.add(UUID.randomUUID().toString());
        }

        // Task n belongs to user n / 3, so three tasks share one (user, key) pair.
        List<Reply> replies = race(users * 3, n -> book("retry-user-" + (n / 3), eventId, keys.get(n / 3)));

        assertThat(replies).allSatisfy(r -> assertThat(r.status()).as("%s", r).isIn(201, 409));
        assertThat(replies.stream().filter(r -> r.status() == 409)).allSatisfy(r -> assertThat(r.code()).isIn(
                "REQUEST_IN_PROGRESS",     // a duplicate that arrived while the first was still being processed
                "DUPLICATE_REQUEST"));
        for (int u = 0; u < users; u++) {
            int user = u;
            Set<String> ids = new HashSet<>();
            for (int copy = 0; copy < 3; copy++) {
                Reply r = replies.get(user * 3 + copy);
                if (r.status() == 201) {
                    ids.add(r.bookingId());
                }
            }
            assertThat(ids).as("user %d got one booking id, however many copies succeeded", user).hasSize(1);
        }
        assertThat(liveBookings(eventId)).as("one booking per user, not three").isEqualTo(users);
        assertThat(seatsWithStatus(eventId, "HELD")).isEqualTo(users);
    }

    @Test
    @DisplayName("sold out, then one booking is cancelled: the freed seat goes to the next caller, and only to one of them")
    void cancelledSeatIsSoldAgain() throws Exception {
        long eventId = createEvent(3);
        List<Reply> first = race(3, n -> book("early-" + n, eventId, UUID.randomUUID().toString()));
        assertThat(first).allSatisfy(r -> assertThat(r.status()).isEqualTo(201));
        assertThat(book("late-0", eventId, UUID.randomUUID().toString()).status()).isEqualTo(409);

        mvc.perform(post("/api/v1/bookings/" + first.get(0).bookingId() + "/cancel").header("Authorization", bearer(userOf(first.get(0), eventId))))
                .andExpect(status().isOk());

        List<Reply> second = race(10, n -> book("late-" + n, eventId, UUID.randomUUID().toString()));
        assertThat(tally(second)).containsEntry("201", 1).containsEntry("409 NO_SEAT_AVAILABLE", 9);
        assertThat(liveBookings(eventId)).isEqualTo(3);
    }

    // ------------------------------------------------------------------------------------------ helpers

    private String bearer(String username) {
        return "Bearer " + tokens.issue(username, Role.USER).value();
    }

    private String userOf(Reply reply, long eventId) {
        return jdbc.queryForObject("select user_id from bookings where id = ?", String.class, reply.bookingId());
    }

    private Reply book(String username, long eventId, String idempotencyKey) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/bookings")
                        .header("Authorization", bearer(username)).header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":" + eventId + "}"))
                .andReturn();
        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString();
        String code = status == 201 || body.isBlank() ? "" : json.readTree(body).path("code").asText();
        String id = status == 201 ? json.readTree(body).path("id").asText() : null;
        return new Reply(status, code, id, "true".equals(result.getResponse().getHeader("Idempotent-Replayed")));
    }

    private static Map<String, Integer> tally(List<Reply> replies) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Reply r : replies) {
            counts.merge(r.code().isEmpty() ? String.valueOf(r.status()) : r.status() + " " + r.code(), 1, Integer::sum);
        }
        return counts;
    }

    /** Releases all threads at the same instant; the result list keeps the task order. */
    private List<Reply> race(int threads, TaskWithIndex task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Reply>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                int n = i;
                futures.add(pool.submit((Callable<Reply>) () -> {
                    ready.countDown();
                    go.await();
                    return task.run(n);
                }));
            }
            ready.await();
            go.countDown();
            List<Reply> replies = new ArrayList<>();
            for (Future<Reply> f : futures) {
                replies.add(f.get(150, TimeUnit.SECONDS));
            }
            return replies;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface TaskWithIndex {
        Reply run(int n) throws Exception;
    }

    private long createEvent(int seatCount) {
        String name = "http-test-" + UUID.randomUUID();
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
        return jdbc.queryForObject("select count(*) from bookings where event_id = ? and status in ('PENDING_PAYMENT','CONFIRMED')",
                Long.class, eventId);
    }

    private long distinctLiveSeats(long eventId) {
        return jdbc.queryForObject("select count(distinct seat_id) from bookings where event_id = ? and status in ('PENDING_PAYMENT','CONFIRMED')",
                Long.class, eventId);
    }

    private long seatsWithStatus(long eventId, String status) {
        return jdbc.queryForObject("select count(*) from seats where event_id = ? and status = ?", Long.class, eventId, status);
    }
}
