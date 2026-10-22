// Flash-sale load test: many users compete for a limited number of seats.
//
// What it proves:
//   1. NO OVERSELL      - 201 responses never exceed the seats; availability adds up afterwards.
//   2. NO SERVER ERRORS - every response is 201 (got a seat), 409 (sold out / duplicate in flight) or 429 (never expected here).
//   3. LATENCY          - p95 of the booking call stays under the threshold while everyone hits at once.
//
// Run:  see loadtest/README.md   (env: BASE_URL, EVENT_ID, SEATS, USERS, RETRY_PCT, RUN_ID)
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const EVENT_ID = Number(__ENV.EVENT_ID || 1);
const SEATS = Number(__ENV.SEATS || 100);          // must equal the event's total_seats (the demo seed has 100)
const USERS = Number(__ENV.USERS || 300);          // one virtual user = one buyer
const RETRY_PCT = Number(__ENV.RETRY_PCT || 20);   // share of buyers who double-click: same request, same Idempotency-Key
const RUN_ID = __ENV.RUN_ID || String(Date.now());  // makes usernames unique per run, so reruns never collide

const seatsSold = new Counter('seats_sold_201');
const soldOut = new Counter('sold_out_409');
const replays = new Counter('idempotent_replays');
const unexpected = new Counter('unexpected_responses');   // anything that is not 201 / 409
const oversell = new Counter('oversell_detected');        // set in teardown from the availability endpoint

// 201 and 409 are normal outcomes of a sale, so they must not count as "failed requests".
http.setResponseCallback(http.expectedStatuses(200, 201, 204, 409));

export const options = {
  setupTimeout: '300s',     // registering + logging in hundreds of users does real BCrypt work
  scenarios: {
    // The sale opens: every buyer (one VU, one iteration) fires at the same moment.
    sale: { executor: 'per-vu-iterations', vus: USERS, iterations: 1, maxDuration: '2m', exec: 'buy' },
    // Meanwhile the public page is refreshed constantly.
    browse: { executor: 'constant-arrival-rate', rate: 50, timeUnit: '1s', duration: '20s', preAllocatedVUs: 20, maxVUs: 60, exec: 'browse' },
  },
  thresholds: {
    'unexpected_responses': ['count==0'],                 // no 5xx, no 401, no 429
    'seats_sold_201': [`count<=${SEATS}`],                // the headline: never more winners than seats
    'oversell_detected': ['count==0'],                    // and the database agrees (checked in teardown)
    'http_req_duration{name:book}': ['p(95)<1500'],
    'http_req_duration{name:availability}': ['p(95)<500'],
  },
};

// Runs once before the test: create the buyers and log them in.
export function setup() {
  const tokens = [];
  const batchSize = 20;                                   // gentle on BCrypt (CPU bound) and on the login rate limiter
  for (let start = 0; start < USERS; start += batchSize) {
    const regs = [];
    for (let i = start; i < Math.min(start + batchSize, USERS); i++) {
      regs.push(['POST', `${BASE_URL}/api/v1/auth/register`,
        JSON.stringify({ username: `lt-${RUN_ID}-${i}`, password: 'load-test-password' }), json()]);
    }
    http.batch(regs);
    const logins = [];
    for (let i = start; i < Math.min(start + batchSize, USERS); i++) {
      logins.push(['POST', `${BASE_URL}/api/v1/auth/login`,
        JSON.stringify({ username: `lt-${RUN_ID}-${i}`, password: 'load-test-password' }), json()]);
    }
    for (const res of http.batch(logins)) {
      if (res.status !== 200) {
        throw new Error(`setup: login failed with ${res.status} ${res.body}`);
      }
      tokens.push(res.json('accessToken'));
    }
  }
  const before = availability();
  console.log(`before: ${JSON.stringify(before)}; buyers: ${tokens.length}; seats: ${SEATS}`);
  if (before.available !== SEATS) {
    throw new Error(`setup: event ${EVENT_ID} has ${before.available} free seats, expected ${SEATS}. Run loadtest/reset.sql first.`);
  }
  return { tokens };
}

// One buyer, one request (some of them send it twice, like a double click).
export function buy(data) {
  const token = data.tokens[exec.scenario.iterationInTest % data.tokens.length];   // unique per buyer within the scenario
  const key = uuidv4();
  const params = { headers: { ...json().headers, Authorization: `Bearer ${token}`, 'Idempotency-Key': key }, tags: { name: 'book' } };
  const body = JSON.stringify({ eventId: EVENT_ID });

  record(http.post(`${BASE_URL}/api/v1/bookings`, body, params));
  if (Math.random() * 100 < RETRY_PCT) {
    const retry = http.post(`${BASE_URL}/api/v1/bookings`, body, params);   // same key: must not create a second booking
    record(retry);
    if (retry.headers['Idempotent-Replayed'] === 'true') {
      replays.add(1);
    }
  }
}

export function browse() {
  const res = http.get(`${BASE_URL}/api/v1/events/${EVENT_ID}/availability`, { tags: { name: 'availability' } });
  check(res, { 'availability 200': (r) => r.status === 200 });
}

// Runs once after everything: the database must agree that nothing was oversold.
export function teardown() {
  sleep(1);
  const after = availability();
  console.log(`after: ${JSON.stringify(after)}`);
  const total = after.available + after.held + after.booked;
  if (total !== SEATS || after.held + after.booked > SEATS) {
    console.error(`OVERSELL OR LOST SEATS: ${JSON.stringify(after)} (expected total ${SEATS})`);
    oversell.add(1);
  }
}

function record(res) {
  if (res.status === 201) {
    seatsSold.add(1);
  } else if (res.status === 409) {
    soldOut.add(1);
  } else {
    unexpected.add(1);
    console.error(`unexpected ${res.status}: ${res.body}`);
  }
}

function availability() {
  return http.get(`${BASE_URL}/api/v1/events/${EVENT_ID}/availability`).json();
}

function json() {
  return { headers: { 'Content-Type': 'application/json' } };
}
