package com.sunny.flashsale.payment.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param enabled false switches the relay, the consumer and topic creation off (tests that do not need Kafka)
 */
@ConfigurationProperties(prefix = "payment.outbox")
public record OutboxProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("50") int batchSize,
        @DefaultValue("PT0.5S") Duration pollInterval,
        @DefaultValue("PT5S") Duration sendTimeout,
        @DefaultValue("P7D") Duration retention) {
}
