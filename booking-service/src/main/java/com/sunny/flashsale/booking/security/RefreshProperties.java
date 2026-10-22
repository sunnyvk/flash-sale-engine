package com.sunny.flashsale.booking.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** @param ttl lifetime of one refresh token. Each use issues a new one, so an active user stays logged in. */
@ConfigurationProperties(prefix = "auth.refresh")
public record RefreshProperties(@DefaultValue("P7D") Duration ttl) {
}
