package com.sunny.flashsale.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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

/**
 * The payment half of the saga against real MySQL and Kafka: booking events in, payment events out.
 * Skipped automatically when Docker is not running.
 */
@SpringBootTest(properties = {
        "spring.flyway.locations=classpath:db/migration",
        "payment.outbox.poll-interval=PT0.2S",
        "payment.retry.attempts=3",
        "payment.retry.delay-ms=200",
        "payment.retry.multiplier=1.0",
        "payment.retry.max-delay-ms=1000",
        "payment.gateway.declined-user-ids=poor-user",
        "payment.gateway.outage-user-ids=unlucky-user"
})
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class PaymentSagaKafkaIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4").withDatabaseName("payment_db");

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

    @Autowired JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("BOOKING_HELD: the user is charged and PAYMENT_SUCCEEDED is published for that booking")
    void heldLeadsToSuccess() {
        String booking = id();
        publish(booking, bookingEvent(UUID.randomUUID().toString(), "BOOKING_HELD", booking, "alice"));

        List<JsonNode> events = paymentEvents(booking, 1);

        assertThat(events.get(0).get("type").asText()).isEqualTo("PAYMENT_SUCCEEDED");
        assertThat(status(booking)).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("card declined: PAYMENT_FAILED with the reason is published (this is what makes booking-service compensate)")
    void declinedLeadsToFailure() {
        String booking = id();
        publish(booking, bookingEvent(UUID.randomUUID().toString(), "BOOKING_HELD", booking, "poor-user"));

        List<JsonNode> events = paymentEvents(booking, 1);

        assertThat(events.get(0).get("type").asText()).isEqualTo("PAYMENT_FAILED");
        assertThat(events.get(0).get("reason").asText()).isEqualTo("Card declined");
        assertThat(status(booking)).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("the same booking held twice (redelivery): one payment row and exactly one PAYMENT_SUCCEEDED")
    void duplicateHeldChargesOnce() throws Exception {
        String booking = id();
        publish(booking, bookingEvent(UUID.randomUUID().toString(), "BOOKING_HELD", booking, "alice"));
        paymentEvents(booking, 1);

        publish(booking, bookingEvent(UUID.randomUUID().toString(), "BOOKING_HELD", booking, "alice"));
        Thread.sleep(3000);

        assertThat(jdbc.queryForObject("select count(*) from payments where booking_id = ?", Integer.class, booking)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ?", Integer.class, booking)).isEqualTo(1);
    }

    @Test
    @DisplayName("BOOKING_CANCELLED after a successful payment: refunded, PAYMENT_REFUNDED published after PAYMENT_SUCCEEDED")
    void cancelledAfterSuccessRefunds() {
        String booking = id();
        publish(booking, bookingEvent(UUID.randomUUID().toString(), "BOOKING_HELD", booking, "alice"));
        paymentEvents(booking, 1);

        publish(booking, bookingEvent(UUID.randomUUID().toString(), "BOOKING_CANCELLED", booking, "alice"));
        List<JsonNode> events = paymentEvents(booking, 2);

        assertThat(events).extracting(e -> e.get("type").asText()).containsExactly("PAYMENT_SUCCEEDED", "PAYMENT_REFUNDED");
        assertThat(status(booking)).isEqualTo("REFUNDED");
    }

    @Test
    @DisplayName("BOOKING_CANCELLED after a declined payment (normal compensation): nothing more happens")
    void cancelledAfterFailureIsQuiet() throws Exception {
        String booking = id();
        publish(booking, bookingEvent(UUID.randomUUID().toString(), "BOOKING_HELD", booking, "poor-user"));
        paymentEvents(booking, 1);

        publish(booking, bookingEvent(UUID.randomUUID().toString(), "BOOKING_CANCELLED", booking, "poor-user"));
        Thread.sleep(3000);

        assertThat(status(booking)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ?", Integer.class, booking)).isEqualTo(1);
    }

    @Test
    @DisplayName("provider outage: retried on the retry topics, then dead-lettered; the payment stays PENDING and nothing is announced")
    void outageEndsInDeadLetterTopic() {
        String booking = id();
        publish(booking, bookingEvent(UUID.randomUUID().toString(), "BOOKING_HELD", booking, "unlucky-user"));

        List<ConsumerRecord<String, String>> dead = read("booking.events-payment-dlt", booking, 1);

        assertThat(dead.get(0).key()).isEqualTo(booking);
        assertThat(status(booking)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ?", Integer.class, booking)).isZero();
    }

    @Test
    @DisplayName("malformed JSON is not retried: it goes straight to the dead-letter topic")
    void malformedGoesToDlt() {
        String poison = "{not json " + UUID.randomUUID();
        publish("poison-key", poison);

        assertThat(read("booking.events-payment-dlt", poison, 1)).hasSize(1);
    }

    // ------------------------------------------------------------------------------------------ helpers

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static String bookingEvent(String eventId, String type, String bookingId, String userId) {
        return "{\"eventId\":\"" + eventId + "\",\"type\":\"" + type + "\",\"occurredAt\":\"2026-10-18T05:00:00Z\","
                + "\"bookingId\":\"" + bookingId + "\",\"userId\":\"" + userId + "\",\"saleEventId\":1,\"seatId\":1,"
                + "\"amount\":100.00,\"reason\":null}";
    }

    private String status(String booking) {
        return jdbc.queryForObject("select status from payments where booking_id = ?", String.class, booking);
    }

    private void publish(String key, String value) {
        Map<String, Object> cfg = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(cfg)) {
            producer.send(new ProducerRecord<>("booking.events", key, value)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** payment.events records for one booking, parsed. Waits until at least {@code expected} have arrived. */
    private List<JsonNode> paymentEvents(String booking, int expected) {
        List<JsonNode> parsed = new ArrayList<>();
        for (ConsumerRecord<String, String> r : read("payment.events", booking, expected)) {
            try {
                parsed.add(json.readTree(r.value()));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return parsed;
    }

    /** Reads a topic from the start; keeps records whose key or value contains {@code needle}. */
    private List<ConsumerRecord<String, String>> read(String topic, String needle, int expected) {
        Map<String, Object> cfg = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(cfg)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            while (found.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (needle.equals(r.key()) || (r.value() != null && r.value().contains(needle))) found.add(r);
                });
            }
        }
        assertThat(found).as("records on %s containing %s", topic, needle).hasSize(expected);
        return found;
    }
}
