package com.sunny.flashsale.booking.security;

import com.sunny.flashsale.common.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginAttemptLimiterTest {

    private static final String KEY = "auth:login:alice:10.0.0.1";

    @Mock StringRedisTemplate redis;

    private LoginAttemptLimiter limiter() {
        return new LoginAttemptLimiter(redis, new LoginLimitProperties(3, Duration.ofMinutes(15)));
    }

    @Test
    @DisplayName("attempts up to the limit pass; the counter key is per username AND ip, and the TTL is passed in milliseconds")
    void allowsUpToTheLimit() {
        when(redis.execute(eq(LoginAttemptLimiter.INCR_WITH_TTL), eq(java.util.List.of(KEY)), eq("900000"))).thenReturn(1L, 2L, 3L);
        LoginAttemptLimiter limiter = limiter();

        assertThatCode(() -> {
            limiter.beforeAttempt("alice", "10.0.0.1");
            limiter.beforeAttempt("alice", "10.0.0.1");
            limiter.beforeAttempt("alice", "10.0.0.1");
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the attempt after the limit is refused with 429 LOGIN_RATE_LIMITED")
    void blocksOverTheLimit() {
        when(redis.execute(eq(LoginAttemptLimiter.INCR_WITH_TTL), anyList(), any())).thenReturn(4L);

        assertThatThrownBy(() -> limiter().beforeAttempt("alice", "10.0.0.1"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.getCode()).isEqualTo(LoginAttemptLimiter.RATE_LIMITED);
                });
    }

    @Test
    @DisplayName("Redis down: fails open, login is not blocked")
    void failsOpen() {
        when(redis.execute(eq(LoginAttemptLimiter.INCR_WITH_TTL), anyList(), any())).thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> limiter().beforeAttempt("alice", "10.0.0.1")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a successful login deletes the counter; a Redis error there is swallowed")
    void successResetsCounter() {
        limiter().onSuccess("alice", "10.0.0.1");
        verify(redis).delete(KEY);

        doThrow(new RedisConnectionFailureException("down")).when(redis).delete(KEY);
        assertThatCode(() -> limiter().onSuccess("alice", "10.0.0.1")).doesNotThrowAnyException();
    }
}
