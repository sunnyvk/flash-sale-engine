-- The outbox relay is generic: each row says which Kafka topic it belongs to (phase 5 adds a payment topic).
ALTER TABLE outbox_events
    ADD COLUMN topic VARCHAR(100) NOT NULL DEFAULT 'booking.events' AFTER event_type;
