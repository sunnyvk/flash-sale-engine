package com.sunny.flashsale.booking.config;

import com.sunny.flashsale.booking.service.LockingStrategy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Business knobs, all overridable by environment variable (see application.yml).
 *
 * @param holdTtl         how long a seat stays HELD while payment is pending
 * @param lockingStrategy which seat-hold strategy handles "book this specific seat"
 */
@ConfigurationProperties(prefix = "booking")
public record BookingProperties(
        @DefaultValue("PT5M") Duration holdTtl,
        @DefaultValue("ATOMIC_UPDATE") LockingStrategy lockingStrategy) {
}
