-- Phase 6: accounts. The JWT subject is the username, which is also what bookings.user_id already stores.

CREATE TABLE users (
    id            CHAR(36)     NOT NULL,
    username      VARCHAR(64)  NOT NULL,        -- stored lower-case; the unique key makes "alice" and "Alice" one account
    password_hash VARCHAR(100) NOT NULL,        -- BCrypt (60 chars today; headroom for a stronger algorithm later)
    role          VARCHAR(16)  NOT NULL,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_users_username (username),
    CONSTRAINT chk_users_role CHECK (role IN ('USER', 'ADMIN'))
) ENGINE = InnoDB;
