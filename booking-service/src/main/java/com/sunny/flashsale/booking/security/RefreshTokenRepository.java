package com.sunny.flashsale.booking.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** All timestamps are UTC. Times come from the application Clock, so tests control them. */
@Repository
public class RefreshTokenRepository {

    public record Row(String id, String familyId, String username, Instant expiresAt, Instant usedAt, Instant revokedAt) {
    }

    private final JdbcTemplate jdbc;

    public RefreshTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String id, String familyId, String username, String tokenHash, Instant expiresAt) {
        jdbc.update("insert into refresh_tokens (id, family_id, username, token_hash, expires_at) values (?, ?, ?, ?, ?)",
                id, familyId, username, tokenHash, utc(expiresAt));
    }

    public Optional<Row> findByHash(String tokenHash) {
        List<Row> rows = jdbc.query(
                "select id, family_id, username, expires_at, used_at, revoked_at from refresh_tokens where token_hash = ?",
                (rs, i) -> new Row(rs.getString("id"), rs.getString("family_id"), rs.getString("username"),
                        instant(rs.getObject("expires_at", LocalDateTime.class)),
                        instant(rs.getObject("used_at", LocalDateTime.class)),
                        instant(rs.getObject("revoked_at", LocalDateTime.class))),
                tokenHash);
        return rows.stream().findFirst();
    }

    /**
     * Spends the old token and stores its successor in ONE transaction. The conditional UPDATE is the lock: of two
     * simultaneous requests carrying the same token exactly one sees "1 row changed"; the other gets false.
     */
    @Transactional
    public boolean rotate(String oldHash, String newId, String familyId, String username, String newHash,
                          Instant newExpiresAt, Instant now) {
        int spent = jdbc.update("update refresh_tokens set used_at = ? where token_hash = ? "
                        + "and used_at is null and revoked_at is null and expires_at > ?",
                utc(now), oldHash, utc(now));
        if (spent != 1) {
            return false;
        }
        insert(newId, familyId, username, newHash, newExpiresAt);
        return true;
    }

    public int revokeFamily(String familyId, Instant now) {
        return jdbc.update("update refresh_tokens set revoked_at = ? where family_id = ? and revoked_at is null",
                utc(now), familyId);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
