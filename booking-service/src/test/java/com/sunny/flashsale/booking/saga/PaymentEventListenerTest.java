package com.sunny.flashsale.booking.saga;

import com.sunny.flashsale.common.events.MalformedEventException;
import com.sunny.flashsale.common.events.PaymentEvent;
import com.sunny.flashsale.common.events.PaymentEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentEventListenerTest {

    private final PaymentEventListener listener = new PaymentEventListener(Jackson2ObjectMapperBuilder.json().build(), null);

    private static final String VALID = "{\"eventId\":\"e-1\",\"type\":\"PAYMENT_SUCCEEDED\",\"occurredAt\":\"2026-10-18T05:00:00Z\","
            + "\"bookingId\":\"b-1\",\"userId\":\"alice\",\"amount\":10.00,\"reason\":null}";

    @Test
    @DisplayName("parses a well-formed payment event")
    void valid() {
        PaymentEvent event = listener.parse(VALID);

        assertThat(event.type()).isEqualTo(PaymentEventType.PAYMENT_SUCCEEDED);
        assertThat(event.bookingId()).isEqualTo("b-1");
    }

    @Test
    @DisplayName("garbage, missing bookingId and unknown type are MalformedEventException")
    void malformed() {
        assertThatThrownBy(() -> listener.parse("not json")).isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> listener.parse(VALID.replace("\"bookingId\":\"b-1\"", "\"bookingId\":null"))).isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> listener.parse(VALID.replace("PAYMENT_SUCCEEDED", "PAYMENT_TELEPORTED"))).isInstanceOf(MalformedEventException.class);
    }
}
