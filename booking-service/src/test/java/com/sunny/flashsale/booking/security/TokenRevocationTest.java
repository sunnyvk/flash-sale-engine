package com.sunny.flashsale.booking.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenRevocationTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> ops;

    private TokenRevocationList list;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(ops);
        list = new TokenRevocationList(redis, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Jwt jwtWithId(String jti) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "HS256").subject("alice").issuedAt(NOW).expiresAt(NOW.plusSeconds(900));
        if (jti != null) {
            b.jti(jti);
        }
        return b.build();
    }

    @Test
    @DisplayName("revoke stores the jti until the token's own expiry, so the list cleans itself")
    void revokeUsesRemainingLifetime() {
        list.revoke("j-1", NOW.plusSeconds(600));

        verify(ops).set("auth:revoked:j-1", "1", Duration.ofSeconds(600));
    }

    @Test
    @DisplayName("revoking an already-expired token writes nothing")
    void expiredTokenIsNotStored() {
        list.revoke("j-1", NOW.minusSeconds(1));

        verifyNoInteractions(ops);
    }

    @Test
    @DisplayName("a Redis failure while revoking is NOT swallowed, so logout reports the failure")
    void revokeFailurePropagates() {
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> list.revoke("j-1", NOW.plusSeconds(60))).isInstanceOf(RedisConnectionFailureException.class);
    }

    @Test
    @DisplayName("isRevoked reflects Redis, and fails open when Redis is down")
    void isRevoked() {
        when(redis.hasKey("auth:revoked:j-1")).thenReturn(true);
        when(redis.hasKey("auth:revoked:j-2")).thenReturn(false);
        when(redis.hasKey("auth:revoked:j-3")).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(list.isRevoked("j-1")).isTrue();
        assertThat(list.isRevoked("j-2")).isFalse();
        assertThat(list.isRevoked("j-3")).as("fail open").isFalse();
    }

    @Test
    @DisplayName("validator rejects a revoked jti, accepts others, and accepts a token with no jti without asking Redis")
    void validator() {
        when(redis.hasKey("auth:revoked:bad")).thenReturn(true);
        when(redis.hasKey("auth:revoked:good")).thenReturn(false);
        RevokedTokenValidator validator = new RevokedTokenValidator(list);

        OAuth2TokenValidatorResult revoked = validator.validate(jwtWithId("bad"));
        assertThat(revoked.hasErrors()).isTrue();
        assertThat(revoked.getErrors().iterator().next().getDescription()).contains("revoked");
        assertThat(validator.validate(jwtWithId("good")).hasErrors()).isFalse();
        assertThat(validator.validate(jwtWithId(null)).hasErrors()).isFalse();
        verify(redis, never()).hasKey("auth:revoked:null");
    }
}
