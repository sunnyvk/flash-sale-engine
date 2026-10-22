-- Phase 6b: refresh tokens. Only a SHA-256 hash of the token is stored, so a database leak does not hand out usable tokens.
-- Tokens are single-use (rotation). All tokens descended from one login share family_id, so reuse of an old token
-- can revoke the whole family.

CREATE TABLE refresh_tokens (
    id         CHAR(36)    NOT NULL,
    family_id  CHAR(36)    NOT NULL,
    username   VARCHAR(64) NOT NULL,
    token_hash CHAR(64)    NOT NULL,            -- hex SHA-256 of the 256-bit random token
    expires_at DATETIME(3) NOT NULL,            -- UTC
    used_at    DATETIME(3) NULL,                -- set when exchanged for a new token; a second use means theft or a replay
    revoked_at DATETIME(3) NULL,                -- set on logout or when reuse is detected
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_refresh_token_hash (token_hash),
    KEY idx_refresh_family (family_id),
    KEY idx_refresh_expires (expires_at)        -- for a future purge of expired rows
) ENGINE = InnoDB;
