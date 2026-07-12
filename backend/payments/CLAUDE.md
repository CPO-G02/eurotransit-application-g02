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
- The gateway call goes through `PaymentGateway`. Today the only implementation
  is `MockPaymentGateway`: it declines amounts above `app.gateway.decline-above`
  (default 500.00) with `insufficient_funds`, otherwise authorizes. This rule is
  a human-approved simulation so the 402 branch is demonstrable end-to-end.

## Data model
- `transactions(transaction_id, order_id, user_id, amount, currency, status,
  reason)` — one row per decision, AUTHORIZED and DECLINED alike. Declined rows
  feed dashboards/logs; the table is also where the future idempotency task will
  return existing transactions from.

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

## Explicitly out of scope (later tasks)
- **Circuit breaker** on the Payments→gateway edge (fallback = respond 402
  `circuit_breaker_open` without reaching the gateway, contract §1.5). The
  `PaymentGateway` interface is the seam it will plug into.
- **Request-level idempotency** (`processed_requests` dedup on `idempotency_key`,
  contract §3.2 Level 2): a duplicated `/authorize` currently charges again.
