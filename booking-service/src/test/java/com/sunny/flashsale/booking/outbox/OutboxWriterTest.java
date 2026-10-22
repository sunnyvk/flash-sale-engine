package com.sunny.flashsale.booking.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunny.flashsale.booking.domain.Booking;
import com.sunny.flashsale.common.events.BookingEvent;
import com.sunny.flashsale.common.events.BookingEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OutboxWriterTest {

    private static final Instant NOW = Instant.parse("2026-10-17T05:00:00Z");

    @Mock JdbcTemplate jdbc;

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

    @Test
    @DisplayName("appends one outbox row: booking id as the Kafka key, the right topic and a JSON payload that round-trips")
    void appendsRow() throws Exception {
        OutboxWriter writer = new OutboxWriter(jdbc, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
        Booking booking = new Booking("b-1", "alice", 7L, 42L, new BigDecimal("1500.00"), "k1");

        writer.append(BookingEventType.BOOKING_CANCELLED, booking, "payment failed");

        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(anyString(), args.capture(), args.capture(), args.capture(), args.capture(), args.capture(), args.capture());
        List<Object> v = args.getAllValues();
        assertThat(v.get(0).toString()).as("event id is a UUID").hasSize(36);
        assertThat(v.get(1)).isEqualTo("BOOKING");
        assertThat(v.get(2)).as("aggregate id doubles as Kafka key").isEqualTo("b-1");
        assertThat(v.get(3)).isEqualTo("BOOKING_CANCELLED");
        assertThat(v.get(4)).isEqualTo("booking.events");

        BookingEvent payload = mapper.readValue((String) v.get(5), BookingEvent.class);
        assertThat(payload.eventId()).isEqualTo(v.get(0));
        assertThat(payload.type()).isEqualTo(BookingEventType.BOOKING_CANCELLED);
        assertThat(payload.occurredAt()).isEqualTo(NOW);
        assertThat(payload.bookingId()).isEqualTo("b-1");
        assertThat(payload.userId()).isEqualTo("alice");
        assertThat(payload.saleEventId()).isEqualTo(7L);
        assertThat(payload.seatId()).isEqualTo(42L);
        assertThat(payload.amount()).isEqualByComparingTo("1500.00");
        assertThat(payload.reason()).isEqualTo("payment failed");
    }

    @Test
    @DisplayName("every event gets its own id, so a consumer can tell two events of the same booking apart")
    void eventIdsAreUnique() {
        OutboxWriter writer = new OutboxWriter(jdbc, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
        Booking booking = new Booking("b-1", "alice", 7L, 42L, new BigDecimal("10.00"), "k1");

        writer.append(BookingEventType.BOOKING_HELD, booking, null);
        writer.append(BookingEventType.BOOKING_CONFIRMED, booking, null);

        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(jdbc, org.mockito.Mockito.times(2)).update(anyString(), args.capture(), args.capture(), args.capture(),
                args.capture(), args.capture(), args.capture());
        List<Object> v = args.getAllValues();
        assertThat(v.get(0)).isNotEqualTo(v.get(6));
    }
}
