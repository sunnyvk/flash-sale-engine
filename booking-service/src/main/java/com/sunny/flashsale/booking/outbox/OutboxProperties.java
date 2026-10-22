package com.sunny.flashsale.booking.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param enabled      false switches the relay and topic creation off (tests that do not need Kafka)
 * @param batchSize    rows locked and published per transaction
 * @param pollInterval pause between relay passes
 * @param sendTimeout  how long one Kafka send may take before the row is left for the next pass
 * @param retention    how long PUBLISHED rows are kept before the cleanup job deletes them
 */
@ConfigurationProperties(prefix = "booking.outbox")
public record OutboxProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("50") int batchSize,
        @DefaultValue("PT0.5S") Duration pollInterval,
        @DefaultValue("PT5S") Duration sendTimeout,
        @DefaultValue("P7D") Duration retention) {
}
