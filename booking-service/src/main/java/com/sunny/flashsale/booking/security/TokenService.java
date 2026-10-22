package com.sunny.flashsale.booking.security;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Mints access tokens. Claims: sub = username, roles, iss, iat, exp, and a random jti (token id) that a later
 * revocation list or audit log can refer to. The token holds NO password and nothing secret: it is only signed,
 * not encrypted, so anyone can read it.
 */
@Service
public class TokenService {

    public record IssuedToken(String value, long expiresInSeconds) {
    }

    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, JwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedToken issue(String username, Role role) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(username)
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .id(UUID.randomUUID().toString())
                .claim("roles", List.of(role.name()))
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new IssuedToken(token, properties.accessTokenTtl().toSeconds());
    }
}
