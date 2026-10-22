-- DEMO DATA ONLY. Lives in a separate Flyway location (db/seed) so production can leave it out:
--   FLYWAY_LOCATIONS=classpath:db/migration
-- One flash sale with exactly 100 seats (A-001 .. A-100): the number the load test tries to oversell.

INSERT INTO flash_events (name, starts_at, total_seats, price)
VALUES ('Demo Flash Sale Concert', DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 30 DAY), 100, 1500.00);

INSERT INTO seats (event_id, seat_number, status)
SELECT e.id, CONCAT('A-', LPAD(n.i, 3, '0')), 'AVAILABLE'
FROM flash_events e
         JOIN (WITH RECURSIVE nums(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM nums WHERE i < 100)
               SELECT i FROM nums) n
WHERE e.name = 'Demo Flash Sale Concert';
