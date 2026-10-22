package com.sunny.flashsale.notification.service;

/** The send failed for a reason that may go away (provider down, timeout): worth retrying. */
public class NotificationDeliveryException extends RuntimeException {

    public NotificationDeliveryException(String message) {
        super(message);
    }
}
