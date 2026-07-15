import http, { expectedStatuses, setResponseCallback } from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const baseUrl = (__ENV.BASE_URL || 'https://g02.cpo2026.it').replace(/\/$/, '');
const token = __ENV.AUTH_TOKEN;
const vus = Number(__ENV.VUS || '40');
const duration = __ENV.DURATION || '2m';
const trainId = __ENV.TRAIN_ID || 'T-100';
const seatClass = __ENV.SEAT_CLASS || 'standard';
const quantity = Number(__ENV.QUANTITY || '1');
const amount = Number(__ENV.AMOUNT || '49.90');

if (!token) {
  fail('AUTH_TOKEN is required; pass a valid Orders audience bearer token via the environment.');
}

setResponseCallback(expectedStatuses({ min: 200, max: 399 }, 409, 429));

export const options = {
  scenarios: {
    order_create_load: {
      executor: 'constant-vus',
      vus,
      duration,
      gracefulStop: '10s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<1000'],
    order_create_unexpected_status: ['rate<0.01'],
    order_create_5xx: ['count==0'],
  },
};

const unexpectedStatus = new Rate('order_create_unexpected_status');
const serverErrors = new Counter('order_create_5xx');
const accepted = new Counter('order_create_202');
const conflicts = new Counter('order_create_409');
const shed = new Counter('order_create_429');

export default function () {
  const idempotencyKey = `k6-${__VU}-${__ITER}-${Date.now()}-${Math.floor(Math.random() * 1000000)}`;
  const payload = JSON.stringify({
    idempotency_key: idempotencyKey,
    user_id: `k6-user-${__VU}`,
    user_email: `k6-user-${__VU}@eurotransit.local`,
    train_id: trainId,
    seat_class: seatClass,
    quantity,
    amount,
    currency: 'EUR',
  });

  const response = http.post(`${baseUrl}/api/v1/orders`, payload, {
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    tags: {
      endpoint: 'POST /api/v1/orders',
    },
  });

  if (response.status === 202) {
    accepted.add(1);
  } else if (response.status === 409) {
    conflicts.add(1);
  } else if (response.status === 429) {
    shed.add(1);
  }

  if (response.status >= 500) {
    serverErrors.add(1);
  }

  const expected = response.status === 202 || response.status === 409 || response.status === 429;
  unexpectedStatus.add(!expected);

  check(response, {
    'order create returned 202, 409, or 429': () => expected,
    'order create did not return 5xx': () => response.status < 500,
    '429 includes Retry-After': (r) => r.status !== 429 || Boolean(r.headers['Retry-After']),
  });
}
