package com.sunny.flashsale.payment.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunny.flashsale.common.events.PaymentEvent;
import com.sunny.flashsale.common.events.PaymentEventType;
import com.sunny.flashsale.common.events.Topics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Same transactional outbox as booking-service: the event row is written in the SAME transaction as the payment's
 * status change, so "payment recorded" and "payment announced" can never disagree. Each service owns its own outbox
 * table (schema-per-service), which is why this small class exists twice instead of living in {@code common}.
 */
@Component
public class OutboxWriter {

    private static final String INSERT = "insert into outbox_events "
            + "(event_id, aggregate_type, aggregate_id, event_type, topic, payload) values (?, ?, ?, ?, ?, CAST(? AS JSON))";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(PaymentEventType type, String bookingId, String userId, BigDecimal amount, String reason) {
        PaymentEvent event = new PaymentEvent(UUID.randomUUID().toString(), type, Instant.now(clock),
                bookingId, userId, amount, reason);
        jdbc.update(INSERT, event.eventId(), "PAYMENT", bookingId, type.name(), Topics.PAYMENT_EVENTS, toJson(event));
    }

    private String toJson(PaymentEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize " + event.type(), e);
        }
    }
}
