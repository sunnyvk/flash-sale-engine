package com.sunny.flashsale.common.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Explicit JSON parsing instead of Kafka's JsonDeserializer: no type headers to trust, no trusted-packages setting,
 * and a bad payload becomes a {@link MalformedEventException} that skips retries and goes to the dead-letter topic.
 */
public final class EventJson {

    private EventJson() {
    }

    public static <T> T parse(ObjectMapper mapper, String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            throw new MalformedEventException("Empty payload", null);
        }
        try {
            T value = mapper.readValue(json, type);
            if (value == null) {
                throw new MalformedEventException("Payload is JSON null", null);
            }
            return value;
        } catch (JsonProcessingException e) {
            throw new MalformedEventException("Not a valid " + type.getSimpleName() + ": " + e.getOriginalMessage(), e);
        }
    }

    public static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new MalformedEventException("Event is missing " + field, null);
        }
        return value;
    }
}
