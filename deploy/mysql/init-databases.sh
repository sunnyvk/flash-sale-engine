#!/bin/bash
# Runs once, on first start of an empty MySQL volume (mysql image convention: /docker-entrypoint-initdb.d).
# One database AND one user per service, each user restricted to its own schema, so a bug or SQL injection in
# notification-service physically cannot touch booking_db.
set -euo pipefail

mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
CREATE DATABASE IF NOT EXISTS booking_db      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS payment_db      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS notification_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'booking'@'%'      IDENTIFIED BY '${BOOKING_DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'payment'@'%'      IDENTIFIED BY '${PAYMENT_DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'notification'@'%' IDENTIFIED BY '${NOTIFICATION_DB_PASSWORD}';

-- ALL on its own schema because Flyway runs migrations as the same user. In a larger setup you would split
-- a DDL-capable migration user from a DML-only runtime user.
GRANT ALL PRIVILEGES ON booking_db.*      TO 'booking'@'%';
GRANT ALL PRIVILEGES ON payment_db.*      TO 'payment'@'%';
GRANT ALL PRIVILEGES ON notification_db.* TO 'notification'@'%';
FLUSH PRIVILEGES;
SQL
