package com.sunny.flashsale.payment.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunny.flashsale.common.events.BookingEvent;
import com.sunny.flashsale.common.events.EventJson;
import com.sunny.flashsale.common.events.MalformedEventException;
import com.sunny.flashsale.common.events.Topics;
import com.sunny.flashsale.payment.service.PaymentService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Consumes booking events. The retry and dead-letter topics carry a payment-specific suffix: several services read
 * booking.events, and with Spring's default names they would all share {@code booking.events-retry-0}, so one
 * service's retries would be re-processed by every other service.
 */
@Component
@ConditionalOnProperty(prefix = "payment.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BookingEventListener {

    private static final Logger log = LoggerFactory.getLogger(BookingEventListener.class);

    private final ObjectMapper mapper;
    private final PaymentService paymentService;

    public BookingEventListener(ObjectMapper mapper, PaymentService paymentService) {
        this.mapper = mapper;
        this.paymentService = paymentService;
    }

    @RetryableTopic(
            attempts = "${payment.retry.attempts}",
            backoff = @Backoff(delayExpression = "${payment.retry.delay-ms}",
                    multiplierExpression = "${payment.retry.multiplier}",
                    maxDelayExpression = "${payment.retry.max-delay-ms}"),
            numPartitions = "3",
            replicationFactor = "1",
            retryTopicSuffix = "-payment-retry",
            dltTopicSuffix = "-payment-dlt",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            exclude = MalformedEventException.class,
            traversingCauses = "true")
    @KafkaListener(topics = Topics.BOOKING_EVENTS)
    public void onBookingEvent(ConsumerRecord<String, String> record) {
        paymentService.handle(parse(record.value()));
    }

    BookingEvent parse(String json) {
        BookingEvent event = EventJson.parse(mapper, json, BookingEvent.class);
        EventJson.require(event.eventId(), "eventId");
        EventJson.require(event.bookingId(), "bookingId");
        EventJson.require(event.userId(), "userId");
        if (event.type() == null) {
            throw new MalformedEventException("Event is missing type", null);
        }
        if (event.amount() == null) {
            throw new MalformedEventException("Event is missing amount", null);
        }
        return event;
    }

    @DltHandler
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        Header reason = record.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        log.error("Message moved to dead-letter topic: topic={} partition={} offset={} key={} reason={} payload={}",
                record.topic(), record.partition(), record.offset(), record.key(),
                reason == null ? "unknown" : new String(reason.value(), StandardCharsets.UTF_8), record.value());
    }
}
