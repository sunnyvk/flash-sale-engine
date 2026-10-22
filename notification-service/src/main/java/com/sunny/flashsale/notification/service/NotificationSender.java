package com.sunny.flashsale.notification.service;

import com.sunny.flashsale.common.events.BookingEvent;

/** The side effect. Throw {@link NotificationDeliveryException} on failure so the message is retried. */
public interface NotificationSender {

    void send(String userId, NotificationType type, BookingEvent event);
}
