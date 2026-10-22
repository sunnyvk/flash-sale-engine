package com.sunny.flashsale.booking.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole chain on the real application: register, login, book with the token, ownership, ADMIN bootstrap.
 * Real MySQL and Redis; Kafka is switched off because nothing here needs it. Skipped automatically without Docker.
 */
@SpringBootTest(properties = {
        "spring.flyway.locations=classpath:db/migration",
        "booking.outbox.enabled=false",
        "auth.bootstrap-admin.username=Root-Admin",
        "auth.bootstrap-admin.password=a-long-admin-password"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class AuthFlowIntegrationTest {

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
    @Autowired ObjectMapper json;

    @Test
    @DisplayName("register -> login -> book with the token -> the booking belongs to the token's user")
    void fullFlow() throws Exception {
        String name = "user-" + UUID.randomUUID().toString().substring(0, 8);
        register(name, "correct-horse-battery");
        String token = login(name, "correct-horse-battery");
        long eventId = createEvent(3);

        MvcResult booked = mvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + token).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":" + eventId + ",\"seatNumber\":\"S-001\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        String bookingId = json.readTree(booked.getResponse().getContentAsString()).get("id").asText();
        assertThat(jdbc.queryForObject("select user_id from bookings where id = ?", String.class, bookingId)).isEqualTo(name);
    }

    @Test
    @DisplayName("passwords are stored as BCrypt hashes, never as plain text")
    void passwordsAreHashed() throws Exception {
        String name = "user-" + UUID.randomUUID().toString().substring(0, 8);
        register(name, "correct-horse-battery");

        String stored = jdbc.queryForObject("select password_hash from users where username = ?", String.class, name);

        assertThat(stored).startsWith("$2").doesNotContain("correct-horse-battery");
    }

    @Test
    @DisplayName("registering the same username twice (different case) is a 409")
    void duplicateUsername() throws Exception {
        String name = "user-" + UUID.randomUUID().toString().substring(0, 8);
        register(name, "correct-horse-battery");

        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name.toUpperCase() + "\",\"password\":\"another-password\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
    }

    @Test
    @DisplayName("wrong password and unknown user both get the same 401")
    void badLogin() throws Exception {
        String name = "user-" + UUID.randomUUID().toString().substring(0, 8);
        register(name, "correct-horse-battery");

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody-" + name + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("a user cannot read another user's booking (404) but the bootstrapped ADMIN can; only ADMIN can confirm")
    void ownershipAndAdmin() throws Exception {
        String alice = "alice-" + UUID.randomUUID().toString().substring(0, 8);
        String bob = "bob-" + UUID.randomUUID().toString().substring(0, 8);
        register(alice, "correct-horse-battery");
        register(bob, "correct-horse-battery");
        String aliceToken = login(alice, "correct-horse-battery");
        String bobToken = login(bob, "correct-horse-battery");
        String adminToken = login("root-admin", "a-long-admin-password");
        long eventId = createEvent(3);

        String bookingId = json.readTree(mvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + aliceToken).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":" + eventId + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();

        mvc.perform(get("/api/v1/bookings/" + bookingId).header("Authorization", "Bearer " + aliceToken)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/bookings/" + bookingId).header("Authorization", "Bearer " + bobToken)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/bookings/" + bookingId).header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk());

        mvc.perform(post("/api/v1/bookings/" + bookingId + "/confirm").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/bookings/" + bookingId + "/confirm").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("the bootstrap admin exists with role ADMIN, and a public registration can never produce an ADMIN")
    void adminRoleOnlyFromBootstrap() throws Exception {
        assertThat(jdbc.queryForObject("select role from users where username = 'root-admin'", String.class)).isEqualTo("ADMIN");

        String name = "user-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"password\":\"correct-horse-battery\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("select role from users where username = ?", String.class, name)).isEqualTo("USER");
    }

    // ------------------------------------------------------------------ rate limiting, refresh tokens, logout

    @Test
    @DisplayName("after 5 wrong passwords even the CORRECT password gets 429: a guesser cannot find the right one by volume")
    void loginIsRateLimited() throws Exception {
        String name = "user-" + UUID.randomUUID().toString().substring(0, 8);
        register(name, "correct-horse-battery");

        for (int i = 0; i < 5; i++) {
            attemptLogin(name, "wrong-password").andExpect(status().isUnauthorized());
        }

        attemptLogin(name, "correct-horse-battery")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMITED"));
    }

    @Test
    @DisplayName("a successful login resets the counter, so occasional typos never lock an honest user out")
    void successResetsTheCounter() throws Exception {
        String name = "user-" + UUID.randomUUID().toString().substring(0, 8);
        register(name, "correct-horse-battery");

        for (int round = 0; round < 3; round++) {                       // 3 x (4 typos + 1 success) = 15 attempts, far above the limit of 5
            for (int i = 0; i < 4; i++) {
                attemptLogin(name, "wrong-password").andExpect(status().isUnauthorized());
            }
            attemptLogin(name, "correct-horse-battery").andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("refresh rotates: new pair works; replaying the OLD refresh token is rejected AND kills the new one (theft response)")
    void refreshRotationAndReuseDetection() throws Exception {
        String name = "user-" + UUID.randomUUID().toString().substring(0, 8);
        register(name, "correct-horse-battery");
        JsonNode first = loginFull(name, "correct-horse-battery");
        String oldRefresh = first.get("refreshToken").asText();

        JsonNode second = refresh(oldRefresh).andExpect(status().isOk()).andReturn().getResponse().getContentAsString().transform(this::parse);
        String newRefresh = second.get("refreshToken").asText();
        assertThat(newRefresh).isNotEqualTo(oldRefresh);
        mvc.perform(get("/api/v1/bookings/" + UUID.randomUUID()).header("Authorization", "Bearer " + second.get("accessToken").asText()))
                .andExpect(status().isNotFound());                      // authenticated (a 401 would mean the new token is bad)

        refresh(oldRefresh).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        refresh(newRefresh).andExpect(status().isUnauthorized());       // the whole family is revoked
    }

    @Test
    @DisplayName("only the hash of a refresh token is in the database")
    void refreshTokenIsStoredHashed() throws Exception {
        String name = "user-" + UUID.randomUUID().toString().substring(0, 8);
        register(name, "correct-horse-battery");
        String raw = loginFull(name, "correct-horse-battery").get("refreshToken").asText();

        List<String> stored = jdbc.queryForList("select token_hash from refresh_tokens where username = ?", String.class, name);

        assertThat(stored).hasSize(1);
        assertThat(stored.get(0)).hasSize(64).isNotEqualTo(raw);
    }

    @Test
    @DisplayName("8 simultaneous refreshes with the same token: exactly one wins (the conditional UPDATE is the lock)")
    void concurrentRefreshHasOneWinner() throws Exception {
        String name = "user-" + UUID.randomUUID().toString().substring(0, 8);
        register(name, "correct-horse-battery");
        String refreshToken = loginFull(name, "correct-horse-battery").get("refreshToken").asText();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return refresh(refreshToken).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        int ok = 0;
        for (Future<Integer> f : results) {
            if (f.get(30, TimeUnit.SECONDS) == 200) {
                ok++;
            }
        }
        pool.shutdownNow();

        assertThat(ok).isEqualTo(1);
    }

    @Test
    @DisplayName("logout: the access token stops working at once (revocation list) and the refresh token dies with its family")
    void logoutRevokesEverything() throws Exception {
        String name = "user-" + UUID.randomUUID().toString().substring(0, 8);
        register(name, "correct-horse-battery");
        JsonNode tokens = loginFull(name, "correct-horse-battery");
        String access = "Bearer " + tokens.get("accessToken").asText();
        String bookingUrl = "/api/v1/bookings/" + UUID.randomUUID();
        mvc.perform(get(bookingUrl).header("Authorization", access)).andExpect(status().isNotFound());   // works before logout

        mvc.perform(post("/api/v1/auth/logout").header("Authorization", access).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + tokens.get("refreshToken").asText() + "\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(get(bookingUrl).header("Authorization", access)).andExpect(status().isUnauthorized());
        refresh(tokens.get("refreshToken").asText()).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------------------------------ helpers

    private JsonNode parse(String body) {
        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ResultActions attemptLogin(String username, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"));
    }

    private JsonNode loginFull(String username, String password) throws Exception {
        return parse(attemptLogin(username, password).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private void register(String username, String password) throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isCreated());
    }

    private String login(String username, String password) throws Exception {
        return loginFull(username, password).get("accessToken").asText();
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
}
