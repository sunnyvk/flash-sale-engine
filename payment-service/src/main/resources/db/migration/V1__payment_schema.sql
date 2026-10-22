-- payment_db: owned exclusively by payment-service.

CREATE TABLE payments (
    id             CHAR(36)       NOT NULL,
    booking_id     CHAR(36)       NOT NULL,  -- reference by value only: no FK across service boundaries
    user_id        VARCHAR(64)    NOT NULL,
    amount         DECIMAL(10, 2) NOT NULL,
    status         VARCHAR(16)    NOT NULL,  -- PENDING -> SUCCEEDED | FAILED
    failure_reason VARCHAR(255)   NULL,
    created_at     DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- A redelivered PaymentRequested event can never charge the same booking twice.
    UNIQUE KEY uq_payment_booking (booking_id),
    CONSTRAINT chk_payment_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT chk_payment_amount CHECK (amount >= 0)
) ENGINE = InnoDB;

CREATE TABLE outbox_events (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    event_id       CHAR(36)    NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id   VARCHAR(64) NOT NULL,
    event_type     VARCHAR(64) NOT NULL,
    payload        JSON        NOT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at   DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_outbox_event_id (event_id),
    KEY idx_outbox_status_id (status, id),
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED'))
) ENGINE = InnoDB;

CREATE TABLE processed_events (
    event_id     CHAR(36)    NOT NULL,
    processed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (event_id)
) ENGINE = InnoDB;
