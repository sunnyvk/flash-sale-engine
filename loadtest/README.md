# Load test: 300 buyers, 100 seats

Goal: prove zero oversell and measure latency while every buyer hits "book" in the same second.
20% of buyers also double-click (same request, same `Idempotency-Key`) to exercise idempotency under load.

## Run (PowerShell, from the repo root)

1. **Infrastructure** (skip if already up):
   `docker compose --env-file .env -f deploy/docker-compose.yml up -d --wait`
2. **Start booking-service ALONE** (the load test measures the booking API; the saga is covered by its own tests).
   Set `OUTBOX_ENABLED=false` in the environment so no Kafka is needed, plus your usual `.env` values (`JWT_SECRET`, DB and Redis passwords).
   Optional tuning knob: `DB_POOL_SIZE` (default 20).
3. **Reset the demo event** to 100 free seats (also deletes earlier `lt-*` load-test users). Use your `BOOKING_DB_PASSWORD`:
   `Get-Content loadtest\reset.sql | docker exec -i fs-mysql mysql -ubooking -pYOUR_BOOKING_DB_PASSWORD booking_db`
   The last line printed must say `free_seats 100`.
4. **Run k6** in Docker (nothing to install). `host.docker.internal` is how the container reaches your laptop:
   ```
   docker run --rm -v "${PWD}/loadtest/k6:/scripts" -e BASE_URL=http://host.docker.internal:8081 grafana/k6 run /scripts/flash-sale.js
   ```
   Or, with k6 installed (`winget install k6`): `k6 run loadtest/k6/flash-sale.js`.
   Knobs: `-e USERS=500 -e SEATS=100 -e RETRY_PCT=20 -e EVENT_ID=1`. Always run step 3 again before another run.

## Reading the result

| Line in the k6 summary | Healthy value |
|---|---|
| `seats_sold_201` | **exactly 100** (never more) |
| `sold_out_409` | USERS - 100 (+ any double-click copies that lost) |
| `unexpected_responses` | **0** (any 5xx, 401, 429 shows up here, with the body in the log) |
| `oversell_detected` | **0** |
| `http_req_duration{name:book}` p(95) | under 1500 ms (the threshold) |
| `idempotent_replays` | roughly the double-clickers that arrived after the first request finished |
| `http_reqs` | requests per second achieved |

Independent proof from the database (the same check the teardown does):
`docker exec -i fs-mysql mysql -ubooking -pYOUR_BOOKING_DB_PASSWORD booking_db -e "select status, count(*) from seats where event_id=1 group by status; select count(*), count(distinct seat_id) from bookings where status in ('PENDING_PAYMENT','CONFIRMED');"`
Expected: 100 HELD seats, and `100 | 100` (as many live bookings as distinct seats).

## If `unexpected_responses` is not 0
- 500/503 with a pool timeout message: the DB pool is too small for the burst. Raise `DB_POOL_SIZE` and rerun; note the before/after numbers, that is exactly the tuning story for the README.
- 429: the login limiter fired (should not happen: every buyer logs in once).
- Setup fails with "has N free seats": run the reset (step 3).
