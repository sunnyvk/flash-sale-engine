package com.sunny.flashsale.notification.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunny.flashsale.common.events.BookingEvent;
import org.springframework.stereotype.Component;

/**
 * Explicit JSON parsing instead of Kafka's JsonDeserializer: no type headers to trust, no trusted-packages setting,
 * and a bad payload becomes a clear {@link MalformedEventException} we can route to the dead-letter topic.
 */
@Component
public class BookingEventParser {

    private final ObjectMapper mapper;

    public BookingEventParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public BookingEvent parse(String json) {
        if (json == null || json.isBlank()) {
            throw new MalformedEventException("Empty payload", null);
        }
        BookingEvent event;
        try {
            event = mapper.readValue(json, BookingEvent.class);
        } catch (JsonProcessingException e) {
            throw new MalformedEventException("Not a valid BookingEvent: " + e.getOriginalMessage(), e);
        }
        if (isBlank(event.eventId()) || event.type() == null || isBlank(event.bookingId()) || isBlank(event.userId())) {
            throw new MalformedEventException("BookingEvent is missing eventId, type, bookingId or userId", null);
        }
        return event;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
