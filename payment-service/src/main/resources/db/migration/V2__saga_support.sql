-- Phase 5: the outbox now carries a destination topic, and a payment can be refunded (saga compensation).

ALTER TABLE outbox_events
    ADD COLUMN topic VARCHAR(100) NOT NULL DEFAULT 'payment.events' AFTER event_type;

-- MySQL cannot alter a CHECK constraint in place: drop and re-create it with the new allowed value.
ALTER TABLE payments DROP CHECK chk_payment_status;
ALTER TABLE payments
    ADD CONSTRAINT chk_payment_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'REFUNDED'));
