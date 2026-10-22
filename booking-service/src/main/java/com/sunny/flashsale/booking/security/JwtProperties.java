package com.sunny.flashsale.booking.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param secret         HMAC key for signing. Anyone holding it can mint tokens, so it comes from the environment
 *                       only, never from a file in the repository
 * @param issuer         the "iss" claim; tokens from any other issuer are rejected
 * @param accessTokenTtl how long a token is valid
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String secret,
        @DefaultValue("https://booking.flashsale.local") String issuer,
        @DefaultValue("PT15M") Duration accessTokenTtl) {
}
