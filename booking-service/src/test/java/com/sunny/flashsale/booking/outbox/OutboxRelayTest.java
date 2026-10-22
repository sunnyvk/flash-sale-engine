package com.sunny.flashsale.booking.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Captor;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-10-17T05:00:00Z");

    @Mock JdbcTemplate jdbc;
    @Mock KafkaTemplate<String, String> kafka;
    @Mock PlatformTransactionManager txManager;
    @Captor ArgumentCaptor<ProducerRecord<String, String>> sent;
    @Captor ArgumentCaptor<List<Object[]>> marked;

    private final CompletableFuture<SendResult<String, String>> ok = CompletableFuture.completedFuture(null);

    private OutboxRelay relay(int batchSize) {
        OutboxProperties props = new OutboxProperties(true, batchSize, Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofDays(7));
        return new OutboxRelay(jdbc, kafka, new TransactionTemplate(txManager), props, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void pending(int batchSize, List<OutboxRelay.Row>... batches) {
        doReturn(batches[0], java.util.Arrays.stream(batches).skip(1).toArray())
                .when(jdbc).query(anyString(), any(RowMapper.class), eq(batchSize));
    }

    private static OutboxRelay.Row row(long id, String aggregateId, String type) {
        return new OutboxRelay.Row(id, "event-" + id, "booking.events", aggregateId, type, "{\"n\":" + id + "}");
    }

    @BeforeEach
    void noop() {
        // relay instances are created per test because batch size differs
    }

    @Test
    @DisplayName("publishes pending rows in id order, keyed by booking id, with headers, then marks exactly those rows PUBLISHED")
    void publishesInOrderAndMarks() {
        pending(10, List.of(row(1, "b-1", "BOOKING_HELD"), row(2, "b-1", "BOOKING_CONFIRMED")));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(ok);

        int published = relay(10).publishBatch();

        assertThat(published).isEqualTo(2);
        verify(kafka, times(2)).send(sent.capture());
        assertThat(sent.getAllValues()).extracting(r -> r.key()).containsExactly("b-1", "b-1");
        assertThat(sent.getAllValues()).extracting(r -> r.topic()).containsOnly("booking.events");
        assertThat(sent.getAllValues()).extracting(r -> r.value()).containsExactly("{\"n\":1}", "{\"n\":2}");
        assertThat(new String(sent.getAllValues().get(1).headers().lastHeader("event-id").value(), StandardCharsets.UTF_8)).isEqualTo("event-2");
        assertThat(new String(sent.getAllValues().get(1).headers().lastHeader("event-type").value(), StandardCharsets.UTF_8)).isEqualTo("BOOKING_CONFIRMED");
        verify(jdbc).batchUpdate(anyString(), marked.capture());
        assertThat(marked.getValue()).extracting(a -> a[1]).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("Kafka fails on the 2nd row: the pass stops, only row 1 is marked, rows 2 and 3 stay PENDING in order")
    void stopsAtFirstFailure() {
        pending(10, List.of(row(1, "b-1", "BOOKING_HELD"), row(2, "b-2", "BOOKING_HELD"), row(3, "b-3", "BOOKING_HELD")));
        CompletableFuture<SendResult<String, String>> failed = CompletableFuture.failedFuture(new IllegalStateException("broker down"));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(ok, failed);

        int published = relay(10).publishBatch();

        assertThat(published).isEqualTo(1);
        verify(kafka, times(2)).send(any(ProducerRecord.class));   // row 3 was never attempted
        verify(jdbc).batchUpdate(anyString(), marked.capture());
        assertThat(marked.getValue()).extracting(a -> a[1]).containsExactly(1L);
    }

    @Test
    @DisplayName("Kafka down from the first row: nothing is marked, the relay does not throw")
    void kafkaDownMarksNothing() {
        pending(10, List.of(row(1, "b-1", "BOOKING_HELD")));
        CompletableFuture<SendResult<String, String>> failed = CompletableFuture.failedFuture(new IllegalStateException("broker down"));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(failed);

        assertThat(relay(10).publishBatch()).isZero();

        verify(jdbc, never()).batchUpdate(anyString(), anyList());
    }

    @Test
    @DisplayName("nothing pending: Kafka is not touched")
    void nothingPending() {
        pending(10, List.of());

        assertThat(relay(10).publishBatch()).isZero();

        verifyNoInteractions(kafka);
        verify(jdbc, never()).batchUpdate(anyString(), anyList());
    }

    @Test
    @DisplayName("poll keeps draining while batches come back full, and stops at the first short one")
    void pollDrainsFullBatches() {
        pending(2, List.of(row(1, "b-1", "BOOKING_HELD"), row(2, "b-2", "BOOKING_HELD")), List.of(row(3, "b-3", "BOOKING_HELD")));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(ok);

        relay(2).poll();

        verify(jdbc, times(2)).query(anyString(), any(RowMapper.class), eq(2));
        verify(kafka, times(3)).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("poll swallows database errors so the scheduler keeps running")
    void pollSurvivesDatabaseError() {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"))
                .when(jdbc).query(anyString(), any(RowMapper.class), eq(10));

        relay(10).poll();   // must not throw

        verifyNoInteractions(kafka);
    }

    @Test
    @DisplayName("cleanup deletes PUBLISHED rows older than the retention period")
    void cleanupUsesRetentionCutoff() {
        when(jdbc.update(anyString(), any(LocalDateTime.class))).thenReturn(5);

        relay(10).cleanup();

        verify(jdbc).update(anyString(), eq(LocalDateTime.of(2026, 10, 10, 5, 0)));
    }
}
