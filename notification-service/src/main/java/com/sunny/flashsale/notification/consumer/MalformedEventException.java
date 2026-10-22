package com.sunny.flashsale.notification.consumer;

/**
 * The message can never be processed, however often it is retried (not JSON, wrong shape, unknown type).
 * The listener excludes it from retries, so it goes straight to the dead-letter topic instead of wasting three retries.
 */
public class MalformedEventException extends RuntimeException {

    public MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
