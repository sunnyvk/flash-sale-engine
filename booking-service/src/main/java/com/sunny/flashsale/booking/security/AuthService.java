package com.sunny.flashsale.booking.security;

import com.sunny.flashsale.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;

/**
 * Registration and login.
 * <ul>
 *   <li>Public registration can only ever create a USER. ADMIN accounts come from the bootstrap setting.</li>
 *   <li>Login is rate limited per (username, IP) BEFORE any password work, and returns an access token plus a
 *       single-use refresh token. Logout revokes the access token's jti and the refresh-token family.</li>
 *   <li>Login answers "wrong username" and "wrong password" identically, and does the same BCrypt work either way, so
 *       neither the message nor the response time reveals which usernames exist.</li>
 * </ul>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    public static final String USERNAME_TAKEN = "USERNAME_TAKEN";
    public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final TokenService tokens;
    private final LoginAttemptLimiter limiter;
    private final RefreshTokenService refreshTokens;
    private final TokenRevocationList revocations;
    /** A real hash of a throwaway value: compared against when the user does not exist, to spend the same time. */
    private final String dummyHash;

    /** What login and refresh hand back. */
    public record Session(TokenService.IssuedToken access, String refreshToken) {
    }

    public AuthService(UserRepository users, PasswordEncoder encoder, TokenService tokens, LoginAttemptLimiter limiter,
                       RefreshTokenService refreshTokens, TokenRevocationList revocations) {
        this.users = users;
        this.encoder = encoder;
        this.tokens = tokens;
        this.limiter = limiter;
        this.refreshTokens = refreshTokens;
        this.revocations = revocations;
        this.dummyHash = encoder.encode("not-a-real-password");
    }

    public static String normalize(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    public void register(String username, String password) {
        String name = normalize(username);
        if (!users.insertIfAbsent(name, encoder.encode(password), Role.USER)) {
            throw new ApiException(HttpStatus.CONFLICT, USERNAME_TAKEN, "That username is already taken");
        }
        log.info("User registered: username={}", name);
    }

    public Session login(String username, String password, String clientIp) {
        String name = normalize(username);
        limiter.beforeAttempt(name, clientIp);   // 429 before spending BCrypt time on an attacker
        UserAccount account = users.findByUsername(name).orElse(null);
        boolean matches = encoder.matches(password, account == null ? dummyHash : account.passwordHash());
        if (account == null || !matches) {
            log.info("Login failed: username={} ip={}", name, clientIp);
            throw new ApiException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS, "Invalid username or password");
        }
        limiter.onSuccess(name, clientIp);
        log.info("Login succeeded: username={} role={}", name, account.role());
        return new Session(tokens.issue(account.username(), account.role()), refreshTokens.startFamily(account.username()));
    }

    /** Spends the refresh token and issues a new pair. The role is re-read from the database, so a role change applies at once. */
    public Session refresh(String refreshToken) {
        RefreshTokenService.Rotated rotated = refreshTokens.rotate(refreshToken);
        UserAccount account = users.findByUsername(rotated.username()).orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, RefreshTokenService.INVALID_REFRESH_TOKEN, "Invalid or expired refresh token"));
        return new Session(tokens.issue(account.username(), account.role()), rotated.newToken());
    }

    /** @param refreshToken optional; when given, the whole refresh family is revoked too */
    public void logout(String accessTokenId, Instant accessTokenExpiry, String refreshToken) {
        revocations.revoke(accessTokenId, accessTokenExpiry);
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokens.revokeFamilyOf(refreshToken);
        }
        log.info("Logout: jti={}", accessTokenId);
    }
}
