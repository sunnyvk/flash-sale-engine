package com.sunny.flashsale.booking.security;

import com.sunny.flashsale.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Refresh tokens: opaque, random, single-use.
 * <ul>
 *   <li><b>Opaque random string, not a JWT.</b> It must be revocable and checked against the database anyway, so a
 *       signed self-contained token adds nothing. 256 bits from SecureRandom cannot be guessed.</li>
 *   <li><b>Only the SHA-256 hash is stored.</b> The token is already high-entropy, so a fast hash is enough (BCrypt is
 *       for low-entropy passwords) and a leaked table is useless to an attacker.</li>
 *   <li><b>Rotation with reuse detection.</b> Every refresh spends the token and issues a new one in the same family.
 *       If an already-spent token shows up again, someone is replaying a stolen copy: the whole family is revoked and
 *       both the thief and the real user must log in again. Trade-off: a client that fires two refreshes at once with
 *       the same token also trips this, so clients must serialise refreshes.</li>
 * </ul>
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    public static final String INVALID_REFRESH_TOKEN = "INVALID_REFRESH_TOKEN";

    public record Rotated(String username, String newToken) {
    }

    private final RefreshTokenRepository repository;
    private final RefreshProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository repository, RefreshProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /** Called at login: starts a new family and returns the raw token (shown to the client once, never stored). */
    public String startFamily(String username) {
        String raw = newRawToken();
        String familyId = UUID.randomUUID().toString();
        repository.insert(UUID.randomUUID().toString(), familyId, username, hash(raw), clock.instant().plus(properties.ttl()));
        return raw;
    }

    public Rotated rotate(String rawToken) {
        Instant now = clock.instant();
        String oldHash = hash(rawToken);
        RefreshTokenRepository.Row row = repository.findByHash(oldHash).orElseThrow(RefreshTokenService::invalid);

        if (row.revokedAt() != null) {
            throw invalid();
        }
        if (row.usedAt() != null) {
            return reuseDetected(row, now);
        }
        if (!row.expiresAt().isAfter(now)) {
            throw invalid();
        }

        String newRaw = newRawToken();
        boolean rotated = repository.rotate(oldHash, UUID.randomUUID().toString(), row.familyId(), row.username(),
                hash(newRaw), now.plus(properties.ttl()), now);
        if (!rotated) {
            return reuseDetected(row, now);   // lost a race with another use of the same token
        }
        return new Rotated(row.username(), newRaw);
    }

    /** Logout: kills the whole family. Unknown tokens are ignored, so this reveals nothing. */
    public void revokeFamilyOf(String rawToken) {
        repository.findByHash(hash(rawToken)).ifPresent(row -> repository.revokeFamily(row.familyId(), clock.instant()));
    }

    private Rotated reuseDetected(RefreshTokenRepository.Row row, Instant now) {
        int revoked = repository.revokeFamily(row.familyId(), now);
        log.warn("Refresh token reuse detected, family revoked: username={} family={} tokensRevoked={}",
                row.username(), row.familyId(), revoked);
        throw invalid();
    }

    private String newRawToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // SHA-256 is guaranteed by every JVM
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, INVALID_REFRESH_TOKEN, "Invalid or expired refresh token");
    }
}
