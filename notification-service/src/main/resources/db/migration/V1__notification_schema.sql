-- notification_db: owned exclusively by notification-service.

CREATE TABLE notifications (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    event_id   CHAR(36)     NOT NULL,          -- id of the Kafka event that triggered this notification
    user_id    VARCHAR(64)  NOT NULL,
    type       VARCHAR(32)  NOT NULL,          -- BOOKING_CONFIRMED | BOOKING_RELEASED
    channel    VARCHAR(16)  NOT NULL DEFAULT 'EMAIL',  -- simulated: we log instead of sending real email
    payload    JSON         NOT NULL,
    status     VARCHAR(16)  NOT NULL DEFAULT 'SENT',
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- Doubles as the consumer dedupe table: a redelivered event hits this constraint instead of
    -- sending the user two emails. One table fewer than processed_events, and the check is atomic with the insert.
    UNIQUE KEY uq_notification_event (event_id),
    KEY idx_notification_user_created (user_id, created_at),
    CONSTRAINT chk_notification_status CHECK (status IN ('SENT', 'FAILED'))
) ENGINE = InnoDB;
