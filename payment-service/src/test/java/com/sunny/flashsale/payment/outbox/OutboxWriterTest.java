package com.sunny.flashsale.payment.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunny.flashsale.common.events.PaymentEvent;
import com.sunny.flashsale.common.events.PaymentEventType;
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

    private static final Instant NOW = Instant.parse("2026-10-18T05:00:00Z");

    @Mock JdbcTemplate jdbc;

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

    @Test
    @DisplayName("appends one outbox row: booking id as Kafka key, payment.events as topic, a JSON payload that round-trips")
    void appendsRow() throws Exception {
        new OutboxWriter(jdbc, mapper, Clock.fixed(NOW, ZoneOffset.UTC))
                .append(PaymentEventType.PAYMENT_FAILED, "b-1", "alice", new BigDecimal("100.00"), "Card declined");

        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(anyString(), args.capture(), args.capture(), args.capture(), args.capture(), args.capture(), args.capture());
        List<Object> v = args.getAllValues();
        assertThat(v.get(1)).isEqualTo("PAYMENT");
        assertThat(v.get(2)).as("aggregate id doubles as Kafka key").isEqualTo("b-1");
        assertThat(v.get(3)).isEqualTo("PAYMENT_FAILED");
        assertThat(v.get(4)).isEqualTo("payment.events");

        PaymentEvent payload = mapper.readValue((String) v.get(5), PaymentEvent.class);
        assertThat(payload.eventId()).isEqualTo(v.get(0));
        assertThat(payload.type()).isEqualTo(PaymentEventType.PAYMENT_FAILED);
        assertThat(payload.occurredAt()).isEqualTo(NOW);
        assertThat(payload.bookingId()).isEqualTo("b-1");
        assertThat(payload.amount()).isEqualByComparingTo("100.00");
        assertThat(payload.reason()).isEqualTo("Card declined");
    }
}
