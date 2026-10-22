package com.sunny.flashsale.common.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventJsonTest {

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

    private static final String PAYMENT = "{\"eventId\":\"e-1\",\"type\":\"PAYMENT_FAILED\",\"occurredAt\":\"2026-10-18T05:00:00Z\","
            + "\"bookingId\":\"b-1\",\"userId\":\"alice\",\"amount\":10.00,\"reason\":\"Card declined\"}";

    @Test
    @DisplayName("parses a PaymentEvent, including the Instant and the BigDecimal")
    void parses() {
        PaymentEvent event = EventJson.parse(mapper, PAYMENT, PaymentEvent.class);

        assertThat(event.type()).isEqualTo(PaymentEventType.PAYMENT_FAILED);
        assertThat(event.reason()).isEqualTo("Card declined");
        assertThat(event.amount()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("null, blank, 'null', broken JSON and unknown enum values are all MalformedEventException")
    void rejects() {
        assertThatThrownBy(() -> EventJson.parse(mapper, null, PaymentEvent.class)).isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> EventJson.parse(mapper, " ", PaymentEvent.class)).isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> EventJson.parse(mapper, "null", PaymentEvent.class)).isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> EventJson.parse(mapper, "{oops", PaymentEvent.class)).isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> EventJson.parse(mapper, PAYMENT.replace("PAYMENT_FAILED", "NOPE"), PaymentEvent.class))
                .isInstanceOf(MalformedEventException.class);
    }

    @Test
    @DisplayName("require() rejects null and blank, returns the value otherwise")
    void require() {
        assertThat(EventJson.require("x", "f")).isEqualTo("x");
        assertThatThrownBy(() -> EventJson.require(null, "f")).isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> EventJson.require("  ", "f")).isInstanceOf(MalformedEventException.class);
    }
}
