package com.sunny.flashsale.booking.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param inProgressTtl how long a claimed-but-unfinished key blocks retries; must outlast a normal booking
 *                      but be short, because a crashed request holds the key until this expires
 * @param completedTtl  how long a finished key keeps answering retries (24h is the common convention)
 */
@ConfigurationProperties(prefix = "booking.idempotency")
public record IdempotencyProperties(
        @DefaultValue("PT30S") Duration inProgressTtl,
        @DefaultValue("PT24H") Duration completedTtl) {
}
