package com.sunny.flashsale.booking.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunny.flashsale.booking.domain.Booking;
import com.sunny.flashsale.common.events.BookingEvent;
import com.sunny.flashsale.common.events.BookingEventType;
import com.sunny.flashsale.common.events.Topics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Step one of the transactional outbox: record "this event must be published" in the SAME database transaction as the
 * state change. Nothing talks to Kafka here, so a Kafka outage can never fail or half-complete a booking.
 * <p>
 * JdbcTemplate rather than JPA: the payload column is JSON, and a plain INSERT avoids Hibernate JSON-type mapping
 * (and its schema validation) for a table the application only ever appends to.
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

    /**
     * MANDATORY: calling this without an open transaction throws, instead of silently writing an event that is not
     * tied to the state change. That is the whole guarantee of the pattern, so the framework enforces it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(BookingEventType type, Booking booking, String reason) {
        BookingEvent event = new BookingEvent(UUID.randomUUID().toString(), type, Instant.now(clock),
                booking.getId(), booking.getUserId(), booking.getEventId(), booking.getSeatId(),
                booking.getAmount(), reason);
        jdbc.update(INSERT, event.eventId(), "BOOKING", event.bookingId(), type.name(), Topics.BOOKING_EVENTS, toJson(event));
    }

    private String toJson(BookingEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize " + event.type(), e);
        }
    }
}
