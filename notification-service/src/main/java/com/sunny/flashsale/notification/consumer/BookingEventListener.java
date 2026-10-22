package com.sunny.flashsale.notification.consumer;

import com.sunny.flashsale.common.events.BookingEvent;
import com.sunny.flashsale.common.events.Topics;
import com.sunny.flashsale.notification.service.NotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Non-blocking retries: a failing message is moved to {@code booking.events-notification-retry-0/1/2} and delayed there, so it does
 * not hold up the healthy messages behind it on the main topic. After the last attempt it lands on
 * {@code booking.events-notification-dlt}. Malformed messages skip the retries and go straight to the dead-letter topic.
 */
@Component
public class BookingEventListener {

    private static final Logger log = LoggerFactory.getLogger(BookingEventListener.class);

    private final BookingEventParser parser;
    private final NotificationService notificationService;

    public BookingEventListener(BookingEventParser parser, NotificationService notificationService) {
        this.parser = parser;
        this.notificationService = notificationService;
    }

    @RetryableTopic(
            attempts = "${notification.retry.attempts}",
            backoff = @Backoff(delayExpression = "${notification.retry.delay-ms}",
                    multiplierExpression = "${notification.retry.multiplier}",
                    maxDelayExpression = "${notification.retry.max-delay-ms}"),
            numPartitions = "3",   // must match the main topic so a record keeps its partition (and order) on retry
            replicationFactor = "1",
            retryTopicSuffix = "-notification-retry",   // own topics: payment-service also reads booking.events
            dltTopicSuffix = "-notification-dlt",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            exclude = MalformedEventException.class,
            traversingCauses = "true")
    @KafkaListener(topics = Topics.BOOKING_EVENTS)
    public void onBookingEvent(ConsumerRecord<String, String> record) {
        BookingEvent event = parser.parse(record.value());
        notificationService.handle(event);
    }

    /** Last stop. Alert on this in phase 10; a person decides whether to fix and replay or discard. */
    @DltHandler
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        Header reason = record.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        log.error("Message moved to dead-letter topic: topic={} partition={} offset={} key={} reason={} payload={}",
                record.topic(), record.partition(), record.offset(), record.key(),
                reason == null ? "unknown" : new String(reason.value(), StandardCharsets.UTF_8), record.value());
    }
}
