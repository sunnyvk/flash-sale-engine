package com.sunny.flashsale.notification;

import com.sunny.flashsale.common.events.BookingEvent;
import com.sunny.flashsale.notification.service.NotificationSender;
import com.sunny.flashsale.notification.service.NotificationDeliveryException;
import com.sunny.flashsale.notification.service.NotificationType;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Consumer against real Kafka and MySQL: dedupe, non-blocking retries, dead-letter topic. Skipped without Docker. */
@SpringBootTest(properties = {
        "spring.flyway.locations=classpath:db/migration",
        "notification.retry.attempts=4",
        "notification.retry.delay-ms=200",
        "notification.retry.multiplier=1.0",
        "notification.retry.max-delay-ms=1000"
})
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class NotificationKafkaIntegrationTest {

    private static final String TOPIC = "booking.events";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4").withDatabaseName("notification_db");

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

    /** Replaces the logging sender: counts attempts per user and can be told to fail the next N sends. */
    static class ControllableSender implements NotificationSender {
        private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();

        void failNext(String user, int times) {
            failures.put(user, new AtomicInteger(times));
        }

        int attempts(String user) {
            AtomicInteger a = attempts.get(user);
            return a == null ? 0 : a.get();
        }

        @Override
        public void send(String userId, NotificationType type, BookingEvent event) {
            attempts.computeIfAbsent(userId, u -> new AtomicInteger()).incrementAndGet();
            AtomicInteger left = failures.get(userId);
            if (left != null && left.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                throw new NotificationDeliveryException("simulated failure for " + userId);
            }
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        ControllableSender controllableSender() {
            return new ControllableSender();
        }
    }

    @Autowired ControllableSender sender;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("BOOKING_CONFIRMED event: exactly one notification row is written and the user is notified once")
    void confirmedCreatesNotification() {
        String user = "u-" + UUID.randomUUID();
        publish("b-1", event(UUID.randomUUID().toString(), "BOOKING_CONFIRMED", user));

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(rows(user)).isEqualTo(1));
        assertThat(sender.attempts(user)).isEqualTo(1);
    }

    @Test
    @DisplayName("the same event delivered twice is processed once")
    void redeliveredEventProcessedOnce() {
        String user = "u-" + UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        String marker = "m-" + UUID.randomUUID();
        publish("b-2", event(eventId, "BOOKING_CONFIRMED", user));
        publish("b-2", event(eventId, "BOOKING_CONFIRMED", user));   // redelivery
        publish("b-2", event(UUID.randomUUID().toString(), "BOOKING_CONFIRMED", marker));   // same key => same partition => processed after the duplicate

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(rows(marker)).isEqualTo(1));
        assertThat(rows(user)).isEqualTo(1);
        assertThat(sender.attempts(user)).isEqualTo(1);
    }

    @Test
    @DisplayName("BOOKING_HELD is ignored")
    void heldIsIgnored() {
        String user = "u-" + UUID.randomUUID();
        String marker = "m-" + UUID.randomUUID();
        publish("b-3", event(UUID.randomUUID().toString(), "BOOKING_HELD", user));
        publish("b-3", event(UUID.randomUUID().toString(), "BOOKING_CONFIRMED", marker));

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(rows(marker)).isEqualTo(1));
        assertThat(rows(user)).isZero();
        assertThat(sender.attempts(user)).isZero();
    }

    @Test
    @DisplayName("transient failure (2 failures): retried on the retry topics, succeeds on the 3rd attempt, one row")
    void transientFailureIsRetried() {
        String user = "u-" + UUID.randomUUID();
        sender.failNext(user, 2);
        publish("b-4", event(UUID.randomUUID().toString(), "BOOKING_CONFIRMED", user));

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(rows(user)).isEqualTo(1));
        assertThat(sender.attempts(user)).isEqualTo(3);
    }

    @Test
    @DisplayName("permanent failure: 4 attempts, then the message lands on booking.events-notification-dlt and no row exists")
    void permanentFailureGoesToDlt() {
        String user = "u-" + UUID.randomUUID();
        sender.failNext(user, 1000);
        publish("b-5", event(UUID.randomUUID().toString(), "BOOKING_CONFIRMED", user));

        List<ConsumerRecord<String, String>> dead = readDlt(user, 1);

        assertThat(dead.get(0).key()).isEqualTo("b-5");
        assertThat(sender.attempts(user)).isEqualTo(4);
        assertThat(rows(user)).isZero();
    }

    @Test
    @DisplayName("malformed JSON is not retried: it goes straight to the dead-letter topic")
    void malformedGoesStraightToDlt() {
        String poison = "{not json " + UUID.randomUUID();
        publish("b-6", poison);

        List<ConsumerRecord<String, String>> dead = readDlt(poison, 1);

        assertThat(dead.get(0).value()).isEqualTo(poison);
    }

    // ------------------------------------------------------------------------------------------ helpers

    private static String event(String eventId, String type, String userId) {
        return "{\"eventId\":\"" + eventId + "\",\"type\":\"" + type + "\",\"occurredAt\":\"2026-10-17T05:00:00Z\","
                + "\"bookingId\":\"b\",\"userId\":\"" + userId + "\",\"saleEventId\":1,\"seatId\":1,\"amount\":10.00,\"reason\":null}";
    }

    private void publish(String key, String value) {
        Map<String, Object> cfg = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(cfg)) {
            producer.send(new ProducerRecord<>(TOPIC, key, value)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reads booking.events-notification-dlt from the start until {@code expected} records containing {@code needle} are found. */
    private List<ConsumerRecord<String, String>> readDlt(String needle, int expected) {
        Map<String, Object> cfg = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(cfg)) {
            consumer.subscribe(List.of(TOPIC + "-notification-dlt"));
            long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            while (found.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (r.value().contains(needle)) found.add(r);
                });
            }
        }
        assertThat(found).as("dead-letter records containing %s", needle).hasSize(expected);
        return found;
    }

    private int rows(String user) {
        return jdbc.queryForObject("select count(*) from notifications where user_id = ?", Integer.class, user);
    }
}
