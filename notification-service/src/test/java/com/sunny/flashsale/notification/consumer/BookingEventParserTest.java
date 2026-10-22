package com.sunny.flashsale.notification.consumer;

import com.sunny.flashsale.common.events.BookingEvent;
import com.sunny.flashsale.common.events.BookingEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingEventParserTest {

    private final BookingEventParser parser = new BookingEventParser(Jackson2ObjectMapperBuilder.json().build());

    private static final String VALID = "{\"eventId\":\"e-1\",\"type\":\"BOOKING_CONFIRMED\",\"occurredAt\":\"2026-10-17T05:00:00Z\","
            + "\"bookingId\":\"b-1\",\"userId\":\"alice\",\"saleEventId\":1,\"seatId\":42,\"amount\":1500.00,\"reason\":null}";

    @Test
    @DisplayName("parses a well-formed event")
    void parsesValidEvent() {
        BookingEvent event = parser.parse(VALID);

        assertThat(event.eventId()).isEqualTo("e-1");
        assertThat(event.type()).isEqualTo(BookingEventType.BOOKING_CONFIRMED);
        assertThat(event.bookingId()).isEqualTo("b-1");
        assertThat(event.userId()).isEqualTo("alice");
        assertThat(event.seatId()).isEqualTo(42L);
        assertThat(event.amount()).isEqualByComparingTo("1500.00");
    }

    @Test
    @DisplayName("a field added by a newer producer is ignored, so producers can evolve first")
    void ignoresUnknownFields() {
        String withExtra = VALID.replace("\"reason\":null", "\"reason\":null,\"newField\":\"x\"");

        assertThat(parser.parse(withExtra).eventId()).isEqualTo("e-1");
    }

    @Test
    @DisplayName("not JSON: MalformedEventException (goes straight to the dead-letter topic)")
    void notJson() {
        assertThatThrownBy(() -> parser.parse("{this is not json")).isInstanceOf(MalformedEventException.class);
    }

    @Test
    @DisplayName("empty or null payload: MalformedEventException")
    void emptyPayload() {
        assertThatThrownBy(() -> parser.parse("  ")).isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(MalformedEventException.class);
    }

    @Test
    @DisplayName("unknown event type: MalformedEventException")
    void unknownType() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("BOOKING_CONFIRMED", "BOOKING_TELEPORTED")))
                .isInstanceOf(MalformedEventException.class);
    }

    @Test
    @DisplayName("missing userId or eventId: MalformedEventException")
    void missingRequiredFields() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("\"userId\":\"alice\"", "\"userId\":null")))
                .isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> parser.parse(VALID.replace("\"eventId\":\"e-1\"", "\"eventId\":\"\"")))
                .isInstanceOf(MalformedEventException.class);
    }
}
