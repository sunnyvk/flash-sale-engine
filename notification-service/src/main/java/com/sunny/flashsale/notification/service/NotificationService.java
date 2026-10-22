package com.sunny.flashsale.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunny.flashsale.common.events.BookingEvent;
import com.sunny.flashsale.common.events.BookingEventType;
import com.sunny.flashsale.notification.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent consumer. Recording the event and sending run in one transaction:
 * <ul>
 *   <li>duplicate delivery: the insert finds the event already recorded and nothing is sent twice;</li>
 *   <li>send fails: the exception rolls the insert back, so the retry is not mistaken for a duplicate.</li>
 * </ul>
 * Remaining gap (inherent to any external side effect): if the send succeeds but the commit then fails, the retry
 * sends once more. That is at-least-once delivery; a provider-side idempotency key would close it.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repository;
    private final NotificationSender sender;
    private final ObjectMapper mapper;

    public NotificationService(NotificationRepository repository, NotificationSender sender, ObjectMapper mapper) {
        this.repository = repository;
        this.sender = sender;
        this.mapper = mapper;
    }

    @Transactional
    public void handle(BookingEvent event) {
        NotificationType type = typeFor(event.type());
        if (type == null) {
            log.debug("Event type {} needs no notification: eventId={}", event.type(), event.eventId());
            return;
        }
        if (!repository.insertIfNew(event.eventId(), event.userId(), type, toJson(event))) {
            log.info("Duplicate event ignored: eventId={} bookingId={}", event.eventId(), event.bookingId());
            return;
        }
        sender.send(event.userId(), type, event);
    }

    private static NotificationType typeFor(BookingEventType type) {
        return switch (type) {
            case BOOKING_CONFIRMED -> NotificationType.BOOKING_CONFIRMED;
            case BOOKING_CANCELLED -> NotificationType.BOOKING_RELEASED;
            case BOOKING_HELD -> null;   // the user is about to be asked to pay; nothing to tell them yet
        };
    }

    private String toJson(BookingEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize event " + event.eventId(), e);
        }
    }
}
