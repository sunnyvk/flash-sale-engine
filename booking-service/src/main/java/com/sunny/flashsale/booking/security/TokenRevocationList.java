package com.sunny.flashsale.booking.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Denylist of revoked access tokens, keyed by the token's {@code jti}: {@code auth:revoked:{jti}}.
 * <ul>
 *   <li>The entry lives only until the token would have expired anyway, so the list never grows without bound.</li>
 *   <li>A JWT is checked by signature alone, so without this list "logout" would not actually stop a token.</li>
 *   <li>{@link #isRevoked} fails OPEN if Redis is down (a revoked token works until it expires, at most 15 minutes);
 *       failing closed would reject every request in the system whenever Redis blips. {@link #revoke} does NOT fail
 *       silently: if the write fails, logout returns an error so the user knows it did not happen.</li>
 * </ul>
 */
@Component
public class TokenRevocationList {

    private static final Logger log = LoggerFactory.getLogger(TokenRevocationList.class);
    private static final String PREFIX = "auth:revoked:";

    private final StringRedisTemplate redis;
    private final Clock clock;

    public TokenRevocationList(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    public void revoke(String jti, Instant expiresAt) {
        Duration remaining = Duration.between(clock.instant(), expiresAt);
        if (remaining.isNegative() || remaining.isZero()) {
            return;   // already expired: nothing left to block
        }
        redis.opsForValue().set(PREFIX + jti, "1", remaining);
    }

    public boolean isRevoked(String jti) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(PREFIX + jti));
        } catch (DataAccessException e) {
            log.warn("Redis unavailable, revocation list not checked: {}", e.getMessage());
            return false;
        }
    }
}
