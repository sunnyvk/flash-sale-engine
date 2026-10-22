package com.sunny.flashsale.booking.saga;

import com.sunny.flashsale.booking.domain.Booking;
import com.sunny.flashsale.booking.service.BookingService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The booking half of the saga against real MySQL and Kafka: payment events in, booking state changes out.
 * Skipped automatically when Docker is not running.
 */
@SpringBootTest(properties = {
        "spring.flyway.locations=classpath:db/migration",
        "booking.outbox.poll-interval=PT0.2S",
        "booking.retry.attempts=3",
        "booking.retry.delay-ms=200",
        "booking.retry.multiplier=1.0",
        "booking.retry.max-delay-ms=1000",
        "booking.reaper.interval=PT1H",        // the tests call the reaper themselves
        "spring.datasource.hikari.maximum-pool-size=20"
})
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class PaymentSagaIntegrationTest {

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
    @Autowired PendingBookingReaper reaper;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("PAYMENT_SUCCEEDED: the booking becomes CONFIRMED and the seat BOOKED")
    void successConfirms() {
        Booking b = book(1);

        publish(b.getId(), paymentEvent("PAYMENT_SUCCEEDED", b.getId(), null));

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(bookingStatus(b)).isEqualTo("CONFIRMED"));
        assertThat(seatStatus(b)).isEqualTo("BOOKED");
    }

    @Test
    @DisplayName("PAYMENT_FAILED: the compensation runs, the booking is CANCELLED with the reason and the seat is back on sale")
    void failureCompensates() {
        Booking b = book(1);

        publish(b.getId(), paymentEvent("PAYMENT_FAILED", b.getId(), "Card declined"));

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(bookingStatus(b)).isEqualTo("CANCELLED"));
        assertThat(seatStatus(b)).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("select failure_reason from bookings where id = ?", String.class, b.getId()))
                .contains("Card declined");
    }

    @Test
    @DisplayName("PAYMENT_FAILED delivered twice: cancelled once, one BOOKING_CANCELLED event")
    void duplicateFailureCancelsOnce() throws Exception {
        Booking b = book(1);
        publish(b.getId(), paymentEvent("PAYMENT_FAILED", b.getId(), "Card declined"));
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(bookingStatus(b)).isEqualTo("CANCELLED"));

        publish(b.getId(), paymentEvent("PAYMENT_FAILED", b.getId(), "Card declined"));
        Thread.sleep(3000);

        assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ? and event_type = 'BOOKING_CANCELLED'",
                Integer.class, b.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("payment arrives AFTER the booking was cancelled: ignored, the seat stays free (payment-service refunds it)")
    void latePaymentDoesNotResurrectBooking() throws Exception {
        Booking b = book(1);
        bookings.cancel(b.getId(), "payment timeout");

        publish(b.getId(), paymentEvent("PAYMENT_SUCCEEDED", b.getId(), null));
        Thread.sleep(3000);

        assertThat(bookingStatus(b)).isEqualTo("CANCELLED");
        assertThat(seatStatus(b)).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("no payment outcome within the hold time: the reaper cancels the booking and frees the seat")
    void reaperCancelsStaleBooking() {
        Booking b = book(1);
        jdbc.update("update bookings set created_at = created_at - interval 1 hour where id = ?", b.getId());

        assertThat(reaper.reapOnce()).isGreaterThanOrEqualTo(1);

        assertThat(bookingStatus(b)).isEqualTo("CANCELLED");
        assertThat(seatStatus(b)).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("select failure_reason from bookings where id = ?", String.class, b.getId()))
                .isEqualTo("payment timeout");
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ? and event_type = 'BOOKING_CANCELLED'",
                Integer.class, b.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("the reaper leaves a fresh booking alone")
    void reaperIgnoresFreshBooking() {
        Booking b = book(1);

        reaper.reapOnce();

        assertThat(bookingStatus(b)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("malformed payment event goes straight to payment.events-booking-dlt")
    void malformedGoesToDlt() {
        String poison = "{not json " + UUID.randomUUID();
        publish("poison-key", poison);

        Map<String, Object> cfg = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<String> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(cfg)) {
            consumer.subscribe(List.of("payment.events-booking-dlt"));
            long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            while (found.isEmpty() && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (poison.equals(r.value())) found.add(r.value());
                });
            }
        }
        assertThat(found).hasSize(1);
    }

    // ------------------------------------------------------------------------------------------ helpers

    private Booking book(int seatCount) {
        long eventId = createEvent(seatCount);
        return bookings.book("alice", eventId, "S-001", UUID.randomUUID().toString());
    }

    private static String paymentEvent(String type, String bookingId, String reason) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"type\":\"" + type + "\",\"occurredAt\":\"2026-10-18T05:00:00Z\","
                + "\"bookingId\":\"" + bookingId + "\",\"userId\":\"alice\",\"amount\":100.00,"
                + "\"reason\":" + (reason == null ? "null" : "\"" + reason + "\"") + "}";
    }

    private void publish(String key, String value) {
        Map<String, Object> cfg = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(cfg)) {
            producer.send(new ProducerRecord<>("payment.events", key, value)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String bookingStatus(Booking b) {
        return jdbc.queryForObject("select status from bookings where id = ?", String.class, b.getId());
    }

    private String seatStatus(Booking b) {
        return jdbc.queryForObject("select status from seats where id = ?", String.class, b.getSeatId());
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
