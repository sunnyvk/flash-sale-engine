package com.sunny.flashsale.payment.consumer;

import com.sunny.flashsale.common.events.BookingEvent;
import com.sunny.flashsale.common.events.BookingEventType;
import com.sunny.flashsale.common.events.MalformedEventException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingEventListenerTest {

    private final BookingEventListener listener = new BookingEventListener(Jackson2ObjectMapperBuilder.json().build(), null);

    private static final String VALID = "{\"eventId\":\"e-1\",\"type\":\"BOOKING_HELD\",\"occurredAt\":\"2026-10-18T05:00:00Z\","
            + "\"bookingId\":\"b-1\",\"userId\":\"alice\",\"saleEventId\":1,\"seatId\":42,\"amount\":1500.00,\"reason\":null}";

    @Test
    @DisplayName("parses a well-formed booking event")
    void valid() {
        BookingEvent event = listener.parse(VALID);

        assertThat(event.type()).isEqualTo(BookingEventType.BOOKING_HELD);
        assertThat(event.amount()).isEqualByComparingTo("1500.00");
    }

    @Test
    @DisplayName("garbage, missing user, missing amount and unknown type are all MalformedEventException (straight to the DLT)")
    void malformed() {
        assertThatThrownBy(() -> listener.parse("{nope")).isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> listener.parse(VALID.replace("\"userId\":\"alice\"", "\"userId\":null"))).isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> listener.parse(VALID.replace("\"amount\":1500.00", "\"amount\":null"))).isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> listener.parse(VALID.replace("BOOKING_HELD", "BOOKING_TELEPORTED"))).isInstanceOf(MalformedEventException.class);
    }
}
