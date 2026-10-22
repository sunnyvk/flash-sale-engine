package com.sunny.flashsale.booking.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param maxAttempts login attempts allowed per (username, IP) in one window; the next one is refused with 429
 * @param window      fixed window, started by the first attempt
 */
@ConfigurationProperties(prefix = "auth.login-limit")
public record LoginLimitProperties(
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("PT15M") Duration window) {
}
