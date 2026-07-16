import http, { expectedStatuses, setResponseCallback } from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const baseUrl = (__ENV.BASE_URL || 'http://localhost:18081').replace(/\/$/, '');
const token = __ENV.PAYMENTS_AUTH_TOKEN || __ENV.AUTH_TOKEN;
const vus = Number(__ENV.VUS || '20');
const duration = __ENV.DURATION || '2m';
const amount = Number(__ENV.AMOUNT || '49.90');

if (!token) {
  fail('PAYMENTS_AUTH_TOKEN or AUTH_TOKEN is required; pass a valid Payments-audience bearer token.');
}

setResponseCallback(expectedStatuses({ min: 200, max: 399 }, 402));

export const options = {
  scenarios: {
    payment_authorize_load: {
      executor: 'constant-vus',
      vus,
      duration,
      gracefulStop: '10s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<1500'],
    payment_authorize_unexpected_status: ['rate<0.01'],
    payment_authorize_5xx: ['count==0'],
  },
};

const unexpectedStatus = new Rate('payment_authorize_unexpected_status');
const serverErrors = new Counter('payment_authorize_5xx');
const authorized = new Counter('payment_authorize_200');
const declined = new Counter('payment_authorize_402');

export default function () {
  const idempotencyKey = `payments-gateway-cb-${__VU}-${__ITER}-${Date.now()}-${Math.floor(Math.random() * 1000000)}`;
  const payload = JSON.stringify({
    idempotency_key: idempotencyKey,
    user_id: `payments-gateway-cb-user-${__VU}`,
    amount,
    currency: 'EUR',
  });

  const response = http.post(`${baseUrl}/api/v1/payments/authorize`, payload, {
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    tags: {
      endpoint: 'POST /api/v1/payments/authorize',
      test: 'payments-gateway-circuit-breaker',
    },
  });

  if (response.status === 200) {
    authorized.add(1);
  } else if (response.status === 402) {
    declined.add(1);
  }

  if (response.status >= 500) {
    serverErrors.add(1);
  }

  const expected = response.status === 200 || response.status === 402;
  unexpectedStatus.add(!expected);

  check(response, {
    'payment authorize returned 200 or 402': () => expected,
    'payment authorize did not return 5xx': () => response.status < 500,
  });
}
