package com.sunny.flashsale.booking.outbox;

import com.sunny.flashsale.booking.domain.Booking;
import com.sunny.flashsale.booking.service.BookingService;
import com.sunny.flashsale.common.error.ApiException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** Outbox -> real Kafka, against real MySQL. Skipped automatically when Docker is not running. */
@SpringBootTest(properties = {
        "spring.flyway.locations=classpath:db/migration",
        "booking.outbox.poll-interval=PT0.2S",
        "booking.outbox.batch-size=20",
        "spring.datasource.hikari.maximum-pool-size=20"
})
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class OutboxRelayIntegrationTest {

    private static final String TOPIC = "booking.events";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4").withDatabaseName("booking_db");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0")
            .withEnv("KAFKA_HEAP_OPTS", "-Xmx512m -Xms256m");

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired BookingService bookings;
    @Autowired OutboxRelay relay;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("book then confirm: HELD then CONFIRMED arrive on Kafka in order, keyed by booking id")
    void heldThenConfirmedInOrder() {
        long eventId = createEvent(3);
        Booking b = bookings.book("alice", eventId, "S-001", UUID.randomUUID().toString());
        bookings.confirm(b.getId());

        List<ConsumerRecord<String, String>> got = consumeFor(b.getId(), 2);

        assertThat(got).extracting(ConsumerRecord::key).containsOnly(b.getId());
        assertThat(got).extracting(r -> header(r, "event-type")).containsExactly("BOOKING_HELD", "BOOKING_CONFIRMED");
        // MySQL normalises JSON (adds spaces, reorders keys), so compare parsed values, never raw text
        assertThat(json(got.get(1).value()).get("bookingId").asText()).isEqualTo(b.getId());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(pending()).isZero());
    }

    @Test
    @DisplayName("book then cancel: HELD then CANCELLED (with the reason) arrive in order")
    void heldThenCancelled() {
        long eventId = createEvent(3);
        Booking b = bookings.book("bob", eventId, "S-001", UUID.randomUUID().toString());
        bookings.cancel(b.getId(), "payment failed");

        List<ConsumerRecord<String, String>> got = consumeFor(b.getId(), 2);

        assertThat(got).extracting(r -> header(r, "event-type")).containsExactly("BOOKING_HELD", "BOOKING_CANCELLED");
        assertThat(got.get(1).value()).contains("payment failed");
    }

    @Test
    @DisplayName("a booking that fails leaves no outbox row behind")
    void failedBookingLeavesNoEvent() {
        long eventId = createEvent(1);
        bookings.book("alice", eventId, "S-001", UUID.randomUUID().toString());
        long before = outboxRows();

        assertThatThrownBy(() -> bookings.book("bob", eventId, "S-001", UUID.randomUUID().toString()))
                .isInstanceOf(ApiException.class);

        assertThat(outboxRows()).isEqualTo(before);
    }

    @Test
    @DisplayName("two relay loops plus the scheduler drain 100 rows: every event is published, none twice")
    void concurrentRelaysPublishEachRowOnce() throws Exception {
        String aggregate = "bulk-" + UUID.randomUUID();
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String id = UUID.randomUUID().toString();
            rows.add(new Object[]{id, aggregate, "BOOKING_HELD", "{\"eventId\":\"" + id + "\",\"n\":" + i + "}"});
        }
        jdbc.batchUpdate("insert into outbox_events (event_id, aggregate_type, aggregate_id, event_type, topic, payload) "
                + "values (?, 'BOOKING', ?, ?, 'booking.events', CAST(? AS JSON))", rows);

        Thread t1 = new Thread(() -> { for (int i = 0; i < 20; i++) relay.publishBatch(); });
        Thread t2 = new Thread(() -> { for (int i = 0; i < 20; i++) relay.publishBatch(); });
        t1.start(); t2.start(); t1.join(); t2.join();

        List<ConsumerRecord<String, String>> got = consumeFor(aggregate, 100);
        assertThat(got).extracting(r -> header(r, "event-id")).doesNotHaveDuplicates().hasSize(100);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(pending()).isZero());
    }

    // ------------------------------------------------------------------------------------------ helpers

    /** Reads the topic from the start and returns records for one aggregate once at least {@code expected} arrived. */
    private List<ConsumerRecord<String, String>> consumeFor(String key, int expected) {
        Map<String, Object> cfg = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> matched = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(cfg)) {
            consumer.subscribe(List.of(TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (matched.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (key.equals(r.key())) matched.add(r);
                });
            }
        }
        assertThat(matched).as("events for %s", key).hasSize(expected);
        return matched;
    }

    private static com.fasterxml.jackson.databind.JsonNode json(String text) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        return new String(r.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private long pending() {
        return jdbc.queryForObject("select count(*) from outbox_events where status = 'PENDING'", Long.class);
    }

    private long outboxRows() {
        return jdbc.queryForObject("select count(*) from outbox_events", Long.class);
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
