package com.sunny.flashsale.booking.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunny.flashsale.common.events.EventJson;
import com.sunny.flashsale.common.events.MalformedEventException;
import com.sunny.flashsale.common.events.PaymentEvent;
import com.sunny.flashsale.common.events.Topics;
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

/** Consumes payment.events with non-blocking retries and its own retry/dead-letter topics. */
@Component
@ConditionalOnProperty(prefix = "booking.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final ObjectMapper mapper;
    private final PaymentOutcomeHandler handler;

    public PaymentEventListener(ObjectMapper mapper, PaymentOutcomeHandler handler) {
        this.mapper = mapper;
        this.handler = handler;
    }

    @RetryableTopic(
            attempts = "${booking.retry.attempts}",
            backoff = @Backoff(delayExpression = "${booking.retry.delay-ms}",
                    multiplierExpression = "${booking.retry.multiplier}",
                    maxDelayExpression = "${booking.retry.max-delay-ms}"),
            numPartitions = "3",
            replicationFactor = "1",
            retryTopicSuffix = "-booking-retry",
            dltTopicSuffix = "-booking-dlt",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            exclude = MalformedEventException.class,
            traversingCauses = "true")
    @KafkaListener(topics = Topics.PAYMENT_EVENTS)
    public void onPaymentEvent(ConsumerRecord<String, String> record) {
        handler.handle(parse(record.value()));
    }

    PaymentEvent parse(String json) {
        PaymentEvent event = EventJson.parse(mapper, json, PaymentEvent.class);
        EventJson.require(event.eventId(), "eventId");
        EventJson.require(event.bookingId(), "bookingId");
        if (event.type() == null) {
            throw new MalformedEventException("Event is missing type", null);
        }
        return event;
    }

    /** A payment outcome that could not be applied. Serious: a seat may stay held until the reaper cancels it. */
    @DltHandler
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        Header reason = record.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        log.error("Payment event moved to dead-letter topic: topic={} partition={} offset={} key={} reason={} payload={}",
                record.topic(), record.partition(), record.offset(), record.key(),
                reason == null ? "unknown" : new String(reason.value(), StandardCharsets.UTF_8), record.value());
    }
}
