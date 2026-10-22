package com.sunny.flashsale.notification.service;

import com.sunny.flashsale.common.events.BookingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Stand-in for a real email/SMS provider: writes a log line. {@code notification.demo.failing-user-ids} lists users
 * whose sends always fail, so retries and the dead-letter topic can be demonstrated without a broken provider.
 */
@Component
public class LoggingNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

    private final Set<String> failingUsers;

    public LoggingNotificationSender(@Value("${notification.demo.failing-user-ids:}") List<String> failingUserIds) {
        this.failingUsers = Set.copyOf(failingUserIds);
    }

    @Override
    public void send(String userId, NotificationType type, BookingEvent event) {
        if (failingUsers.contains(userId)) {
            throw new NotificationDeliveryException("Simulated provider failure for user " + userId);
        }
        log.info("Notification sent: type={} userId={} bookingId={} eventId={}", type, userId, event.bookingId(), event.eventId());
    }
}
