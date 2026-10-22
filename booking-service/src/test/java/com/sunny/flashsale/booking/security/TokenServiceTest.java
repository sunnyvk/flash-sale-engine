package com.sunny.flashsale.booking.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    private final SecretKey key = new SecretKeySpec("unit-test-secret-unit-test-secret-012345".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    private final JwtProperties props = new JwtProperties("unit-test-secret-unit-test-secret-012345", "https://unit-issuer.local", Duration.ofMinutes(15));
    private final TokenService service = new TokenService(new NimbusJwtEncoder(new ImmutableSecret<>(key)), props, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("the token carries sub, roles, iss, a 15 minute expiry and a unique jti, and verifies with the same key")
    void claims() {
        TokenService.IssuedToken token = service.issue("alice", Role.USER);

        Jwt jwt = decoder().decode(token.value());

        assertThat(jwt.getSubject()).isEqualTo("alice");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
        assertThat(jwt.getIssuer().toString()).isEqualTo("https://unit-issuer.local");
        assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(jwt.getId()).isNotBlank();
        assertThat(token.expiresInSeconds()).isEqualTo(900);
    }

    @Test
    @DisplayName("every token gets its own jti")
    void uniqueIds() {
        Jwt a = decode(service.issue("alice", Role.USER).value());
        Jwt b = decode(service.issue("alice", Role.USER).value());

        assertThat(a.getId()).isNotEqualTo(b.getId());
    }

    @Test
    @DisplayName("the token contains no password or hash, only the claims above")
    void noSecretsInToken() {
        Jwt jwt = decode(service.issue("alice", Role.ADMIN).value());

        assertThat(jwt.getClaims().keySet()).containsExactlyInAnyOrder("iss", "sub", "iat", "exp", "jti", "roles");
        assertThat(jwt.getClaimAsStringList("roles")).isEqualTo(List.of("ADMIN"));
    }

    @Test
    @DisplayName("a missing, short or placeholder secret stops the application from starting")
    void weakSecretIsRejected() {
        SecurityConfig config = new SecurityConfig();

        assertThatThrownBy(() -> config.jwtSecretKey(new JwtProperties("too-short", "i", Duration.ofMinutes(1))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("at least 32");
        assertThatThrownBy(() -> config.jwtSecretKey(new JwtProperties(null, "i", Duration.ofMinutes(1))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.jwtSecretKey(new JwtProperties("CHANGE_ME_at_least_32_random_characters", "i", Duration.ofMinutes(1))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CHANGE_ME");
        assertThat(config.jwtSecretKey(props)).isNotNull();
    }

    /** The service uses a fixed clock in the past, so expiry must not be checked against real time here. Signature is still verified. */
    private NimbusJwtDecoder decoder() {
        NimbusJwtDecoder d = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        d.setJwtValidator(t -> OAuth2TokenValidatorResult.success());
        return d;
    }

    private Jwt decode(String token) {
        return decoder().decode(token);
    }
}
