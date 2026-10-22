package com.sunny.flashsale.booking.idempotency;

import com.sunny.flashsale.booking.idempotency.IdempotencyStore.Acquired;
import com.sunny.flashsale.booking.idempotency.IdempotencyStore.Begin;
import com.sunny.flashsale.booking.idempotency.IdempotencyStore.Bypass;
import com.sunny.flashsale.booking.idempotency.IdempotencyStore.InProgress;
import com.sunny.flashsale.booking.idempotency.IdempotencyStore.Mismatch;
import com.sunny.flashsale.booking.idempotency.IdempotencyStore.Replay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisIdempotencyStoreTest {

    private static final String KEY = "idem:booking:alice:k1";
    private static final Duration IN_PROGRESS = Duration.ofSeconds(30);
    private static final Duration COMPLETED = Duration.ofHours(24);

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> ops;

    private RedisIdempotencyStore store;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(ops);
        store = new RedisIdempotencyStore(redis, new IdempotencyProperties(IN_PROGRESS, COMPLETED));
    }

    @Test
    @DisplayName("first request claims the key with SET NX and the short in-progress TTL")
    void claimsKey() {
        when(ops.setIfAbsent(eq(KEY), any(String.class), eq(IN_PROGRESS))).thenReturn(true);

        Begin result = store.begin("alice", "k1", "fp");

        assertThat(result).isInstanceOf(Acquired.class);
        assertThat(((Acquired) result).lockValue()).startsWith("P|fp|");
    }

    @Test
    @DisplayName("same key, same request, still running: InProgress")
    void inProgress() {
        when(ops.setIfAbsent(eq(KEY), any(String.class), eq(IN_PROGRESS))).thenReturn(false);
        when(ops.get(KEY)).thenReturn("P|fp|some-token");

        assertThat(store.begin("alice", "k1", "fp")).isInstanceOf(InProgress.class);
    }

    @Test
    @DisplayName("same key, same request, already done: Replay carries the stored booking id")
    void replay() {
        when(ops.setIfAbsent(eq(KEY), any(String.class), eq(IN_PROGRESS))).thenReturn(false);
        when(ops.get(KEY)).thenReturn("D|fp|booking-123");

        assertThat(store.begin("alice", "k1", "fp")).isEqualTo(new Replay("booking-123"));
    }

    @Test
    @DisplayName("same key, different request: Mismatch")
    void mismatch() {
        when(ops.setIfAbsent(eq(KEY), any(String.class), eq(IN_PROGRESS))).thenReturn(false);
        when(ops.get(KEY)).thenReturn("D|other-fp|booking-123");

        assertThat(store.begin("alice", "k1", "fp")).isInstanceOf(Mismatch.class);
    }

    @Test
    @DisplayName("key expired between SET NX and GET: tries again and claims it")
    void expiredBetweenCalls() {
        when(ops.setIfAbsent(eq(KEY), any(String.class), eq(IN_PROGRESS))).thenReturn(false, true);
        when(ops.get(KEY)).thenReturn(null);

        assertThat(store.begin("alice", "k1", "fp")).isInstanceOf(Acquired.class);
    }

    @Test
    @DisplayName("Redis down: fail open with Bypass instead of failing the booking")
    void redisDown() {
        when(ops.setIfAbsent(eq(KEY), any(String.class), eq(IN_PROGRESS)))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        assertThat(store.begin("alice", "k1", "fp")).isInstanceOf(Bypass.class);
    }

    @Test
    @DisplayName("unreadable stored value: Bypass, the database decides")
    void malformedValue() {
        when(ops.setIfAbsent(eq(KEY), any(String.class), eq(IN_PROGRESS))).thenReturn(false);
        when(ops.get(KEY)).thenReturn("garbage");

        assertThat(store.begin("alice", "k1", "fp")).isInstanceOf(Bypass.class);
    }

    @Test
    @DisplayName("complete stores the booking id with the long TTL")
    void complete() {
        store.complete("alice", "k1", "fp", "booking-123");

        verify(ops).set(KEY, "D|fp|booking-123", COMPLETED);
    }

    @Test
    @DisplayName("complete never throws when Redis is down")
    void completeFailsOpen() {
        doThrow(new RedisConnectionFailureException("down")).when(ops).set(eq(KEY), any(String.class), any(Duration.class));

        assertThatCode(() -> store.complete("alice", "k1", "fp", "booking-123")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("abort runs the compare-and-delete script with our lock value")
    void abort() {
        store.abort("alice", "k1", "P|fp|token");

        verify(redis).execute(eq(RedisIdempotencyStore.DELETE_IF_EQUALS), eq(List.of(KEY)), eq("P|fp|token"));
    }

    @Test
    @DisplayName("abort never throws when Redis is down")
    void abortFailsOpen() {
        doThrow(new RedisConnectionFailureException("down")).when(redis)
                .execute(any(RedisScript.class), anyList(), eq("P|fp|token"));

        assertThatCode(() -> store.abort("alice", "k1", "P|fp|token")).doesNotThrowAnyException();
    }
}
