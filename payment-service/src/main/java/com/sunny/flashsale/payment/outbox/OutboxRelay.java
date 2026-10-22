package com.sunny.flashsale.payment.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Step two of the outbox: a poller that moves PENDING rows to Kafka.
 * <ul>
 *   <li>{@code FOR UPDATE SKIP LOCKED}: two relay instances never publish the same row at the same time.</li>
 *   <li>Rows are sent one by one, in id order, waiting for the broker's ack. On the first failure the pass stops, the
 *       rows already sent are marked PUBLISHED and the failed row stays PENDING, so order is never skipped.</li>
 *   <li>Crash between "sent" and "marked" means the row is sent again later: at-least-once. Consumers de-duplicate.</li>
 * </ul>
 * Known limit: with several relay replicas, events of the same booking could be sent by different replicas out of
 * order. Run one replica of payment-service (as compose does) or partition the relay by aggregate id.
 * The scheduled methods call into a TransactionTemplate, not a @Transactional method, to avoid self-invocation.
 */
@Component
@ConditionalOnProperty(prefix = "payment.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int CLEANUP_CHUNK = 1000;

    private static final String SELECT_PENDING = "select id, event_id, topic, aggregate_id, event_type, payload "
            + "from outbox_events where status = 'PENDING' order by id limit ? for update skip locked";
    private static final String MARK_PUBLISHED = "update outbox_events set status = 'PUBLISHED', published_at = ? where id = ?";
    private static final String DELETE_OLD = "delete from outbox_events where status = 'PUBLISHED' and published_at < ? limit " + CLEANUP_CHUNK;

    private static final RowMapper<Row> ROW_MAPPER = (rs, i) -> new Row(rs.getLong("id"), rs.getString("event_id"),
            rs.getString("topic"), rs.getString("aggregate_id"), rs.getString("event_type"), rs.getString("payload"));

    record Row(long id, String eventId, String topic, String aggregateId, String eventType, String payload) {
    }

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                       OutboxProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${payment.outbox.poll-interval:PT0.5S}")
    public void poll() {
        try {
            int published;
            do {
                published = publishBatch();
            } while (published >= properties.batchSize()); // a full batch means there is probably more waiting
        } catch (RuntimeException e) {
            log.warn("Outbox relay pass failed, will retry: {}", e.toString());
        }
    }

    /** One locked batch in one transaction. Returns how many rows were published. */
    public int publishBatch() {
        Integer published = tx.execute(status -> publishLocked());
        return published == null ? 0 : published;
    }

    private int publishLocked() {
        List<Row> rows = jdbc.query(SELECT_PENDING, ROW_MAPPER, properties.batchSize());
        List<Long> sent = new ArrayList<>();
        try {
            for (Row row : rows) {
                send(row);
                sent.add(row.id());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Outbox relay interrupted after {} of {} rows", sent.size(), rows.size());
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            // Stop here: skipping the failed row and sending later ones would reorder events.
            log.warn("Kafka publish failed after {} of {} rows, leaving the rest PENDING: {}", sent.size(), rows.size(), e.toString());
        }
        markPublished(sent);
        return sent.size();
    }

    private void send(Row row) throws InterruptedException, ExecutionException, TimeoutException {
        ProducerRecord<String, String> record = new ProducerRecord<>(row.topic(), row.aggregateId(), row.payload());
        record.headers().add("event-id", row.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add("event-type", row.eventType().getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void markPublished(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<Object[]> batch = ids.stream().map(id -> new Object[]{now, id}).toList();
        jdbc.batchUpdate(MARK_PUBLISHED, batch);
    }

    /** Without this the outbox table grows forever. Deletes in chunks so it never holds a long lock. */
    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 60_000L)
    public void cleanup() {
        try {
            LocalDateTime cutoff = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).minus(properties.retention());
            int deleted;
            do {
                deleted = jdbc.update(DELETE_OLD, cutoff);
            } while (deleted >= CLEANUP_CHUNK);
        } catch (RuntimeException e) {
            log.warn("Outbox cleanup failed, will retry next hour: {}", e.toString());
        }
    }
}
