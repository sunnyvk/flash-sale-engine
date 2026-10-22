#!/usr/bin/env bash
# End-to-end smoke test against a RUNNING stack (compose, kind or EC2). Needs: curl, jq.
#   BASE_URL=http://localhost:8080 ./deploy/smoke-test.sh
# Proves the whole chain: nginx -> booking (JWT, Redis idempotency, MySQL lock) -> Kafka outbox -> payment-service
# -> Kafka -> booking confirms the seat. Exit code 0 only if every step passed.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
EVENT_ID="${EVENT_ID:-1}"
HEALTH_PATH="${HEALTH_PATH:-/healthz}"   # behind nginx; use /actuator/health/readiness when talking to booking-service directly
USER_NAME="smoke-$(date +%s)-$RANDOM"
PASSWORD="smoke-test-password"

step() { printf '\n== %s\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null || fail "$1 is required"; }
need curl; need jq

step "1. readiness (waits up to 120 s)"
for i in $(seq 1 60); do
  if curl -fsS "$BASE_URL$HEALTH_PATH" | grep -q UP; then echo "ready"; break; fi
  [ "$i" -eq 60 ] && fail "service never became ready at $BASE_URL"
  sleep 2
done

step "2. register + login"
curl -fsS -X POST "$BASE_URL/api/v1/auth/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER_NAME\",\"password\":\"$PASSWORD\"}" >/dev/null
LOGIN=$(curl -fsS -X POST "$BASE_URL/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER_NAME\",\"password\":\"$PASSWORD\"}")
TOKEN=$(echo "$LOGIN" | jq -er .accessToken)
echo "got access token and refresh token: $(echo "$LOGIN" | jq -r '.refreshToken | length') chars"

step "3. protected route without a token is rejected"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/bookings" -H 'Content-Type: application/json' -d "{\"eventId\":$EVENT_ID}")
[ "$CODE" = "401" ] || fail "expected 401 without a token, got $CODE"

step "4. availability (public)"
AVAILABLE=$(curl -fsS "$BASE_URL/api/v1/events/$EVENT_ID/availability" | jq -er .available)
echo "free seats: $AVAILABLE"
[ "$AVAILABLE" -gt 0 ] || fail "no free seats on event $EVENT_ID (reset the demo data)"

step "5. book a seat"
KEY="$(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen)"
BOOKING=$(curl -fsS -X POST "$BASE_URL/api/v1/bookings" -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' -d "{\"eventId\":$EVENT_ID}")
BOOKING_ID=$(echo "$BOOKING" | jq -er .id)
echo "booking $BOOKING_ID is $(echo "$BOOKING" | jq -r .status)"

step "6. replaying the SAME request returns the SAME booking (idempotency)"
REPLAY_HEADERS=$(mktemp)
REPLAY=$(curl -fsS -D "$REPLAY_HEADERS" -X POST "$BASE_URL/api/v1/bookings" -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' -d "{\"eventId\":$EVENT_ID}")
[ "$(echo "$REPLAY" | jq -r .id)" = "$BOOKING_ID" ] || fail "replay created a different booking"
grep -qi '^idempotent-replayed: true' "$REPLAY_HEADERS" || fail "replay was not flagged Idempotent-Replayed"
rm -f "$REPLAY_HEADERS"

step "7. saga: payment-service charges, booking becomes CONFIRMED (waits up to 60 s)"
STATUS=""
for i in $(seq 1 30); do
  STATUS=$(curl -fsS "$BASE_URL/api/v1/bookings/$BOOKING_ID" -H "Authorization: Bearer $TOKEN" | jq -er .status)
  [ "$STATUS" = "CONFIRMED" ] && break
  sleep 2
done
[ "$STATUS" = "CONFIRMED" ] || fail "booking ended as $STATUS, expected CONFIRMED (is payment-service up and Kafka healthy?)"
echo "booking confirmed"

step "8. logout revokes the token"
curl -fsS -X POST "$BASE_URL/api/v1/auth/logout" -H "Authorization: Bearer $TOKEN" >/dev/null
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/api/v1/bookings/$BOOKING_ID" -H "Authorization: Bearer $TOKEN")
[ "$CODE" = "401" ] || fail "revoked token still accepted (HTTP $CODE)"

printf '\nSMOKE TEST PASSED\n'
