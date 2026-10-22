-- Puts the demo event back to "100 free seats, nobody booked" and removes load-test accounts, so the load test can run again.
-- LOCAL DEV ONLY. Never run this against a real database.
-- Run from the repo root (see loadtest/README.md).

SET @event_id = (SELECT id FROM flash_events WHERE name = 'Demo Flash Sale Concert');

DELETE FROM bookings WHERE event_id = @event_id;
UPDATE seats SET status = 'AVAILABLE', held_by = NULL, held_until = NULL, version = 0 WHERE event_id = @event_id;

DELETE FROM refresh_tokens WHERE username LIKE 'lt-%';
DELETE FROM users WHERE username LIKE 'lt-%';
DELETE FROM outbox_events WHERE aggregate_type = 'BOOKING' AND aggregate_id NOT IN (SELECT id FROM bookings);

SELECT @event_id AS event_id, COUNT(*) AS free_seats FROM seats WHERE event_id = @event_id AND status = 'AVAILABLE';
