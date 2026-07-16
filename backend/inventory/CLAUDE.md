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
  A duplicate `/reserve` (same `idempotency_key`) returns the **original**
  `reservation_id` and reserves nothing further — idempotency level 2,
  contract §3.2.
- Kafka consumer of `eurotransit.order-failed` (contract §2.6) for
  **compensation**: if the event carries a `reservation_id`, it releases the
  seats (`available += quantity`); otherwise it's a no-op (nothing was reserved).
  A redelivered `event_id` is skipped — idempotency level 3, contract §3.2.

## Data model
- `seats(train_id, seat_class, available)` — authoritative counts. Distinct from
  Catalog's `available`, which is a display-only snapshot.
- `reservations(reservation_id, order_id, train_id, seat_class, quantity,
  status)` — records what each reservation held, because the `order-failed`
  event doesn't carry train_id/seat_class/quantity. `status` RESERVED→RELEASED
  makes compensation idempotent (release exactly once, even on Kafka redelivery).
- `processed_requests(idempotency_key, reservation_id)` — level 2 sync dedup.
  The claim is inserted **before** the seat decrement, so two concurrent copies
  of the same request cannot both get past it. A 409 rolls the claim back
  (insufficient seats must not be memoised — they can free up later).
- `processed_events(event_id, result, created_at)` — level 3 Kafka dedup, same
  transaction as the compensation it guards. `result` stays NULL; the column
  matches the contract's schema.

The `data.sql` seed is generated (shares Catalog's generator so train_ids match:
`tools/generate_seed.py --target=inventory`). One run/class is pinned to a low
count as a deterministic **demo** sell-out target. No test reads it:
`InventoryReserveTest` wipes `seats` in `@BeforeEach` and builds its own rows, so
"10 concurrent reserves on 5 seats" reserves against `TR-C`, not the seed.

Note: the RESERVED→RELEASED guard alone already prevents a double release, so
`processed_events` is contract compliance + defence in depth, not a bug fix.
`OrderFailedConsumerDedupTest` isolates each layer so either regressing is caught.

## Open decision: replaying /reserve for a compensated order
`processed_requests` maps `order_id → reservation_id` permanently, so a `/reserve`
replayed *after* that reservation was compensated replays `RESERVED` for a
reservation that is actually `RELEASED`. Unreachable in the saga as designed
(Stage 1 runs once per order; Orders' retries all sit inside that one execution),
and contract §1.4 doesn't define the released-replay case — so it is deliberately
left as-is rather than guessed at. Revisit before changing the saga's retry/replay
policy. Full write-up and the options: `consistency-validation.md` §4.
