package com.sunny.flashsale.common.events;

/** The payload can never be processed, however often it is retried. Routed straight to the dead-letter topic. */
public class MalformedEventException extends RuntimeException {

    public MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
