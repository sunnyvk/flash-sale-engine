package com.sunny.flashsale.notification.service;

import com.sunny.flashsale.common.events.BookingEvent;
import com.sunny.flashsale.common.events.BookingEventType;
import com.sunny.flashsale.notification.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock NotificationRepository repository;
    @Mock NotificationSender sender;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(repository, sender, Jackson2ObjectMapperBuilder.json().build());
    }

    private static BookingEvent event(BookingEventType type) {
        return new BookingEvent("e-1", type, Instant.parse("2026-10-17T05:00:00Z"), "b-1", "alice", 1L, 42L,
                new BigDecimal("1500.00"), null);
    }

    @Test
    @DisplayName("BOOKING_CONFIRMED: records the event, then sends a BOOKING_CONFIRMED notification")
    void confirmed() {
        BookingEvent event = event(BookingEventType.BOOKING_CONFIRMED);
        when(repository.insertIfNew(eq("e-1"), eq("alice"), eq(NotificationType.BOOKING_CONFIRMED), anyString())).thenReturn(true);

        service.handle(event);

        verify(sender).send("alice", NotificationType.BOOKING_CONFIRMED, event);
    }

    @Test
    @DisplayName("BOOKING_CANCELLED: sends a BOOKING_RELEASED notification")
    void cancelled() {
        BookingEvent event = event(BookingEventType.BOOKING_CANCELLED);
        when(repository.insertIfNew(eq("e-1"), eq("alice"), eq(NotificationType.BOOKING_RELEASED), anyString())).thenReturn(true);

        service.handle(event);

        verify(sender).send("alice", NotificationType.BOOKING_RELEASED, event);
    }

    @Test
    @DisplayName("BOOKING_HELD needs no notification: nothing is recorded or sent")
    void heldIsIgnored() {
        service.handle(event(BookingEventType.BOOKING_HELD));

        verifyNoInteractions(repository, sender);
    }

    @Test
    @DisplayName("redelivered event (already recorded): the user is not notified twice")
    void duplicateIsIgnored() {
        when(repository.insertIfNew(eq("e-1"), eq("alice"), eq(NotificationType.BOOKING_CONFIRMED), anyString())).thenReturn(false);

        service.handle(event(BookingEventType.BOOKING_CONFIRMED));

        verify(sender, never()).send(anyString(), eq(NotificationType.BOOKING_CONFIRMED), eq(event(BookingEventType.BOOKING_CONFIRMED)));
    }

    @Test
    @DisplayName("send fails: the exception propagates so the transaction rolls back and the message is retried")
    void senderFailurePropagates() {
        BookingEvent event = event(BookingEventType.BOOKING_CONFIRMED);
        when(repository.insertIfNew(eq("e-1"), eq("alice"), eq(NotificationType.BOOKING_CONFIRMED), anyString())).thenReturn(true);
        doThrow(new NotificationDeliveryException("provider down")).when(sender).send("alice", NotificationType.BOOKING_CONFIRMED, event);

        assertThatThrownBy(() -> service.handle(event)).isInstanceOf(NotificationDeliveryException.class);
    }
}
