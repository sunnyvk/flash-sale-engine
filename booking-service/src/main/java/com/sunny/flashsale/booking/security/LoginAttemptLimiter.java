package com.sunny.flashsale.booking.security;

import com.sunny.flashsale.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Brute-force guard: one Redis counter per (username, client IP), {@code auth:login:{username}:{ip}}.
 * <ul>
 *   <li><b>Count first, then check.</b> Every attempt does one atomic INCR. If the result is above the limit the
 *       request is refused BEFORE any password work. Counting after the check would let a burst of parallel requests
 *       all pass the check before any of them was counted.</li>
 *   <li><b>INCR and the TTL are set in one Lua script.</b> With two separate commands a crash between them would leave
 *       a counter with no expiry, locking that user out forever.</li>
 *   <li><b>Key = username + IP, not username alone.</b> A per-username counter would let anyone lock a victim out by
 *       typing wrong passwords for them. The price: an attacker who rotates IPs gets a fresh budget each time
 *       (that needs WAF or CAPTCHA, not an application counter).</li>
 *   <li><b>Fails open</b> when Redis is down, like the idempotency store: login keeps working and the log shows it.</li>
 * </ul>
 */
@Component
public class LoginAttemptLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptLimiter.class);
    public static final String RATE_LIMITED = "LOGIN_RATE_LIMITED";

    static final DefaultRedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>(
            "local c = redis.call('incr', KEYS[1]) if c == 1 then redis.call('pexpire', KEYS[1], ARGV[1]) end return c",
            Long.class);

    private final StringRedisTemplate redis;
    private final LoginLimitProperties properties;

    public LoginAttemptLimiter(StringRedisTemplate redis, LoginLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** Counts this attempt; throws 429 if the limit for the window is already used up. */
    public void beforeAttempt(String username, String clientIp) {
        Long attempts;
        try {
            attempts = redis.execute(INCR_WITH_TTL, List.of(key(username, clientIp)), String.valueOf(properties.window().toMillis()));
        } catch (DataAccessException e) {
            log.warn("Redis unavailable, login rate limit not enforced: {}", e.getMessage());
            return;
        }
        if (attempts != null && attempts > properties.maxAttempts()) {
            log.warn("Login blocked by rate limit: username={} ip={} attempts={}", username, clientIp, attempts);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, RATE_LIMITED, "Too many login attempts. Try again later");
        }
    }

    /** A correct password clears the counter, so honest typos never add up across days. */
    public void onSuccess(String username, String clientIp) {
        try {
            redis.delete(key(username, clientIp));
        } catch (DataAccessException e) {
            log.warn("Could not reset login counter (it will expire on its own): {}", e.getMessage());
        }
    }

    private static String key(String username, String clientIp) {
        return "auth:login:" + username + ":" + clientIp;
    }
}
