package com.sunny.flashsale.booking.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Redis layout, one string per (user, key):
 * <pre>
 *   idem:booking:{userId}:{key} = "P|{fingerprint}|{token}"      request in progress (short TTL)
 *   idem:booking:{userId}:{key} = "D|{fingerprint}|{bookingId}"  request done        (long TTL)
 * </pre>
 * Claiming uses {@code SET key value NX EX ttl}: one atomic command, so two simultaneous retries can never both
 * think they own the key. Every Redis failure degrades to {@link Bypass} instead of failing the booking,
 * because the database constraint still protects correctness.
 */
@Component
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyStore.class);
    private static final String KEY_PREFIX = "idem:booking:";

    // Compare-and-delete must be one atomic step: a plain GET then DEL could delete a key that another request
    // claimed in between (after ours expired). Redis runs a Lua script without interleaving other commands.
    static final DefaultRedisScript<Long> DELETE_IF_EQUALS = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final IdempotencyProperties properties;

    public RedisIdempotencyStore(StringRedisTemplate redis, IdempotencyProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public Begin begin(String userId, String key, String fingerprint) {
        String redisKey = redisKey(userId, key);
        String lockValue = "P|" + fingerprint + "|" + UUID.randomUUID();
        try {
            // Two attempts: the key can expire between a failed SET NX and the GET that follows it.
            for (int attempt = 0; attempt < 2; attempt++) {
                if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(redisKey, lockValue, properties.inProgressTtl()))) {
                    return new Acquired(lockValue);
                }
                String current = redis.opsForValue().get(redisKey);
                if (current != null) {
                    return interpret(current, fingerprint);
                }
            }
            return new InProgress();
        } catch (DataAccessException e) {
            log.warn("Redis unavailable, skipping idempotency fast path: {}", e.getMessage());
            return new Bypass();
        }
    }

    @Override
    public void complete(String userId, String key, String fingerprint, String bookingId) {
        try {
            redis.opsForValue().set(redisKey(userId, key), "D|" + fingerprint + "|" + bookingId, properties.completedTtl());
        } catch (DataAccessException e) {
            // The booking exists; a retry will be answered from MySQL's unique index instead.
            log.warn("Could not record completed idempotency key: {}", e.getMessage());
        }
    }

    @Override
    public void abort(String userId, String key, String lockValue) {
        try {
            redis.execute(DELETE_IF_EQUALS, List.of(redisKey(userId, key)), lockValue);
        } catch (DataAccessException e) {
            log.warn("Could not release idempotency key (it will expire on its own): {}", e.getMessage());
        }
    }

    private static Begin interpret(String stored, String fingerprint) {
        String[] parts = stored.split("\\|", 3);
        if (parts.length < 3) {
            return new Bypass(); // unreadable value: let the database decide
        }
        if (!parts[1].equals(fingerprint)) {
            return new Mismatch();
        }
        return switch (parts[0]) {
            case "D" -> new Replay(parts[2]);
            case "P" -> new InProgress();
            default -> new Bypass();
        };
    }

    private static String redisKey(String userId, String key) {
        return KEY_PREFIX + userId + ":" + key;
    }
}
