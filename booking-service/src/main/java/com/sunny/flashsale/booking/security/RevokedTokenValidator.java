package com.sunny.flashsale.booking.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** Plugged into the JwtDecoder next to the expiry and issuer checks: a revoked jti means 401. */
public class RevokedTokenValidator implements OAuth2TokenValidator<Jwt> {

    private final TokenRevocationList revocations;

    public RevokedTokenValidator(TokenRevocationList revocations) {
        this.revocations = revocations;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String jti = jwt.getId();
        if (jti != null && revocations.isRevoked(jti)) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "The token has been revoked", null));
        }
        return OAuth2TokenValidatorResult.success();
    }
}
