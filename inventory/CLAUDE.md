# Inventory service

Authoritative seat inventory for EuroTransit. Owns the "never oversell"
invariant. Internal service — **not** exposed via Traefik; called synchronously
by Orders' Stage 1.

## Stack
- Kotlin 2.3 / Spring Boot 4.1 / Java 21, WebFlux + coroutines (`suspend fun`).
- Spring Data **R2DBC** (reactive) over PostgreSQL. Its own **CloudNativePG
  cluster `inventory-db`** — database-per-service, no shared DB, no
  cross-service FK (see architecture-design.md §2, §7).
- Spring Kafka consumer (compensation only; Inventory never produces).
- Actuator (`/actuator/health`, `/actuator/prometheus`) + springdoc 3.x
  (Swagger UI), mirroring the Catalog service.
- Seed via `spring.sql.init` (`schema.sql` + `data.sql`), same as Catalog.

## Source of truth
Architecture and contract live in the **configuration repo**:
`docs/architecture-design.md` and `docs/eurotransit-contract.md`. Precedence and
change rules: `docs/ai-guidelines.md`. Do not change API/schema/topics without
approval.

## What it does
- `POST /reserve` (contract §1.4): `{ idempotency_key, train_id, seat_class,
  quantity }` → `200 { reservation_id, status:"RESERVED" }` or
  `409 { status:"INSUFFICIENT_SEATS" }`.
  Reservation is a **single atomic conditional UPDATE**
  (`SET available = available - :qty WHERE available >= :qty`, contract §3.1) —
  never read-then-write — so concurrent requests can't oversell.
- Kafka consumer of `eurotransit.order-failed` (contract §2.6) for
  **compensation**: if the event carries a `reservation_id`, it releases the
  seats (`available += quantity`); otherwise it's a no-op (nothing was reserved).

## Data model
- `seats(train_id, seat_class, available)` — authoritative counts. Distinct from
  Catalog's `available`, which is a display-only snapshot.
- `reservations(reservation_id, order_id, train_id, seat_class, quantity,
  status)` — records what each reservation held, because the `order-failed`
  event doesn't carry train_id/seat_class/quantity. `status` RESERVED→RELEASED
  makes compensation idempotent (release exactly once, even on Kafka redelivery).

The `data.sql` seed is generated (shares Catalog's generator so train_ids match:
`tools/generate_seed.py --target=inventory`). One run/class is pinned to
a low count for the seat concurrency test.

## Explicitly out of scope (later task)
Request-level idempotency (`processed_requests` dedup on `idempotency_key`,
contract §3.2 Level 2) is **not** implemented yet: a duplicated `/reserve`
currently reserves again. This service only guarantees per-request concurrency
correctness for now.
