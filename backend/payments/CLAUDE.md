# Payments service

Authorizes payments for EuroTransit. Internal service — **not** exposed via
Traefik; called synchronously by Orders' Stage 2.

## Stack
- Kotlin 2.3 / Spring Boot 4.1 / Java 21, WebFlux + coroutines (`suspend fun`).
- Spring Data **R2DBC** over PostgreSQL. Its own **CloudNativePG cluster
  `payments-db`** — database-per-service, no shared DB.
- Actuator (`/actuator/health`, `/actuator/prometheus`) + springdoc 3.x
  (Swagger UI), mirroring Catalog/Inventory.
- Schema via `spring.sql.init` (`schema.sql`). No `data.sql` — transactions
  start empty.
- **No Kafka**: Payments has no consumer, producer or outbox (architecture §2).
  The Kafka starter was removed from the skeleton as an incoherent leftover.

## Source of truth
Architecture and contract live in the **configuration repo**:
`docs/architecture-design.md` and `docs/eurotransit-contract.md`. Precedence and
change rules: `docs/ai-guidelines.md`. Do not change API/schema/topics without
approval.

## What it does
- `POST /api/v1/payments/authorize` (contract §1.5): `{ idempotency_key,
  user_id, amount, currency }` → `200 { transaction_id, status:"AUTHORIZED" }`
  or `402 { status:"DECLINED", reason }`.
  A duplicate `/authorize` (same `idempotency_key`) replays the original
  decision without reaching the gateway again — idempotency level 2,
  contract §3.2.
- The gateway call goes through `PaymentGateway`, implemented by
  `HttpPaymentGateway`: a real HTTP call to `payment-gateway-sim`, wrapped in
  its own Resilience4j circuit breaker (instance `payment-gateway`). The
  fallback is `402 DECLINED` with reason `circuit_breaker_open`, without
  reaching the gateway.

## Data model
- `transactions(transaction_id, order_id, user_id, amount, currency, status,
  reason)` — one row per decision, AUTHORIZED and DECLINED alike. Declined rows
  feed dashboards/logs.
- `processed_requests(idempotency_key, transaction_id)` — level 2 sync dedup.
  Written **after** the gateway answers, in the same transaction as the
  `transactions` row (`DecisionRecorder`), so exactly one decision is ever
  recorded per order. The transaction is deliberately not held across the
  gateway call.

## Why a circuit_breaker_open decline is not memoised
It means the gateway never gave a decision (breaker open, or the call
failed/timed out), so there is nothing to deduplicate and memoising it would pin
a transient outage onto the order forever. The `transactions` row is still
written for dashboards; the retry is allowed through, and Stripe's own
`Idempotency-Key: order_id` stops it double-charging.

Unlike Inventory, Payments needs **no compensation / status-transition table**:
`order-failed` is consumed only by Inventory + Notifications, Payments never
touches Kafka, and no documented flow ever reverses an AUTHORIZED payment.

## Security
Distributed JWT validation (architecture pattern B): `SecurityConfig` makes
`/api/v1/payments/**` require a valid Bearer JWT, verified locally against
Keycloak's JWKS (`spring.security.oauth2.resourceserver.jwt.issuer-uri`) with an
audience check (`app.security.jwt.audience`, default `payments`). Actuator and
Swagger stay open. The `jwtDecoder` bean fetches JWKS at startup, so tests mock
`ReactiveJwtDecoder`.

Orders' `PaymentClient` now forwards the same `orders-service` service-account
token already used for Inventory (gated by `app.security.service-token.*`).
Still required for enforcement end-to-end: Keycloak must mint that token with an
`aud` containing `payments`, otherwise Stage 2 (Orders→Payments) 401s.

## Known residual risk
`authorize()` charges before it persists: a pod death in between leaves the
money taken with no local row. The ledger self-heals on Orders' retry (Stripe
returns the same PaymentIntent), but not if the retry budget runs out first.
Closing that needs claim-before-charge plus a reaper for abandoned claims —
bigger than contract §3.2 Level 2 asks for.
