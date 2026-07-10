# AI Interaction Log — Application Repo

This file records significant AI-assisted development sessions in the
application repo, as required by `ai-guidelines.md` §16. Newest entries first.

---

### 2026-07-10 17:30

**Agent**

Claude (Opus 4.8) via Claude Code

**Task**

Implement the Payments service from scratch: the synchronous `POST
/api/v1/payments/authorize` endpoint (contract §1.5), backed by its own
`payments-db`. Circuit breaker (Payments→gateway) and request-level idempotency
(`processed_requests`) explicitly deferred to later tasks.

**Files Created / Modified**

- payments/build.gradle.kts — added actuator, micrometer-registry-prometheus,
  springdoc 3.x, Testcontainers (junit-jupiter/postgresql/r2dbc 1.21.3 +
  spring-boot-testcontainers); **removed spring-boot-starter-kafka(-test)**
- payments/src/main/resources/application.yaml — R2DBC (payments-db),
  spring.sql.init, app.gateway.decline-above, actuator, graceful shutdown
- payments/src/main/resources/schema.sql — transactions (status
  AUTHORIZED/DECLINED + reason). No data.sql (starts empty).
- payments/src/main/kotlin/.../ — entities/TransactionEntity,
  repositories/TransactionRepository, dto, gateway (PaymentGateway interface +
  MockPaymentGateway), service (interface + DefaultPaymentsService),
  controllers/PaymentsController, exceptions/PaymentsExceptionHandler,
  config/OpenApiConfig
- payments/src/test/kotlin/.../PaymentsAuthorizeTest.kt — 4 Testcontainers tests
- payments/CLAUDE.md — module context

**Summary**

Authorize calls a PaymentGateway seam (only MockPaymentGateway today) and
persists one transactions row per decision, then returns 200 AUTHORIZED or
throws PaymentDeclinedException → 402 { status:"DECLINED", reason }. The mock
rule (human-approved via AskUserQuestion) declines amounts above a configurable
threshold (app.gateway.decline-above, default 500.00) with insufficient_funds,
so the 402/payment-failed branch is demonstrable end-to-end.

Verified the Inventory-style schema gap does NOT apply here: transactions is
already documented and no flow ever reverses an AUTHORIZED payment (Payments
never touches Kafka), so there is no reservations-like state/compensation table.

**Potential Risks / Assumptions**

- The mock decline-by-threshold rule is not in the contract; it is a
  human-approved simulation to make the 402 path testable. The real gateway call
  + circuit breaker (fallback 402 circuit_breaker_open) are a later task.
- Request-level idempotency (processed_requests) intentionally not implemented;
  a duplicated /authorize charges again.
- Removed the Kafka starter from the skeleton: architecture states Payments has
  no Kafka involvement at all — the starter was an incoherent leftover.
- The service is deliberately NOT @Transactional: the single insert is atomic, a
  transaction would span the (soon remote) gateway call, and it would roll back
  the DECLINED row when the 402 exception is thrown.
- Tests require Docker in CI.
- payments-db CloudNativePG manifest + SPRING_R2DBC_* secret not yet created
  (config repo).

**Confidence**

High — build and the 4 Testcontainers tests (real PostgreSQL) pass, both wire
shapes (200/402) verified against contract §1.5.

**Notes**

Same JUnit 6 "must not return a value" pitfall avoided in the two WebTestClient
tests (block bodies → Unit).

---

### 2026-07-10 14:45

**Agent**

Claude (Opus 4.8) via Claude Code

**Task**

Implement the Inventory service from scratch: the synchronous `POST /reserve`
endpoint (contract §1.4) and the Kafka compensation consumer for
`eurotransit.order-failed` (contract §2.6), plus the authoritative `inventory-db`
schema and seed and an integration test suite. Request-level idempotency
(`processed_requests`) deliberately deferred to a later task.

**Files Created / Modified**

- inventory/build.gradle.kts — added actuator, micrometer-registry-prometheus,
  springdoc 3.x (coherence with Catalog), and Testcontainers
  (junit-jupiter/postgresql/r2dbc 1.21.3 + spring-boot-testcontainers) for tests
- inventory/src/main/resources/application.yaml — R2DBC (inventory-db),
  spring.sql.init, Kafka consumer (String deserializer), actuator, graceful shutdown
- inventory/src/main/resources/schema.sql — seats (CHECK available >= 0, unique
  train/class) and reservations (status RESERVED/RELEASED)
- inventory/src/main/resources/data.sql — generated authoritative seat seed
- inventory/src/main/kotlin/.../ — entities (SeatEntity, ReservationEntity),
  repositories (SeatRepository atomic reserve/release, ReservationRepository
  markReleased), dto, service (interface + DefaultInventoryService),
  controllers/InventoryController, exceptions/InventoryExceptionHandler,
  config/OpenApiConfig, kafka/OrderFailedConsumer
- inventory/src/test/kotlin/.../InventoryReserveTest.kt — 7 Testcontainers tests
- inventory/CLAUDE.md — module context
- tools/generate_seed.py — moved from catalog/tools/ (now a shared repo-root
  tool), extended with --target=inventory
- catalog/src/main/resources/data.sql — regenerated (header path only)

**Summary**

Never-oversell is guaranteed by a single atomic conditional UPDATE
(`SET available = available - :qty WHERE available >= :qty`), not read-then-write.
A `reservations` table persists what each reservation held, because the
order-failed event carries only `reservation_id`; its status (RESERVED→RELEASED,
flipped atomically) makes compensation idempotent under Kafka redelivery without
needing `processed_events` yet. Both were human-approved over a documented schema
gap (the docs list seats/processed_requests/processed_events but no reservations
table, and the order-failed payload lacks train_id/seat_class/quantity). The seed
generator was recognized as a shared tool, moved to repo-root `tools/`, and
extended to emit Inventory's seat seed from the same source as Catalog so
train_ids match by construction; one run/class is pinned to 5 seats for the
concurrency test.

**Verification**

`./gradlew clean test` — 7/7 pass against a real PostgreSQL (Testcontainers
postgres:16-alpine), including "10 concurrent reserves on 5 seats" (exactly 5
succeed, availability ends at 0) and compensation idempotency on redelivery.
Requires a running Docker daemon.

**Potential Risks / Assumptions**

- The `reservations` table is an approved addition not yet reflected in
  architecture-design.md / eurotransit-contract.md (config repo) — docs should be
  reconciled.
- Request-level idempotency (`processed_requests`) intentionally not implemented;
  a duplicate /reserve currently reserves again.
- Tests require Docker in CI; a pipeline without a Docker daemon will fail them.
- OrderFailedConsumer injects the Jackson 3 (tools.jackson) ObjectMapper —
  exercised by the tests' context load, but new on Boot 4.
- inventory-db CloudNativePG manifest + SPRING_R2DBC_* secret not yet created
  (config repo).

**Confidence**

High — the full integration suite passes against real Postgres and the
concurrency invariant is demonstrated directly.

**Notes**

Avoided the JUnit 6 "test method must not return a value" pitfall (which silently
skips such tests): the two WebTestClient tests use block bodies so they return
Unit. This is the same trap found earlier in the orders test suite.

---

### 2026-07-09 18:24

**Agent**

Claude (Opus 4.8) via Claude Code

**Task**

Make the Catalog service coherent with the team's previous microservices style:
add OpenAPI/Swagger documentation, restructure the exception handler, and add
Spring Boot Actuator (health, probes, Prometheus metrics).

**Files Modified / Created (catalog/)**

- build.gradle.kts — added springdoc-openapi-starter-webflux-ui, actuator, and
  micrometer-registry-prometheus
- src/main/kotlin/.../controllers/CatalogController.kt — @Tag/@Operation/
  @ApiResponses/@Parameter; the 404 response schema points to ErrorResponse
- src/main/kotlin/.../exceptions/CatalogExceptionHandler.kt — introduced a sealed
  CatalogException(status, errorCode, message) base with ProductNotFoundException,
  and a single @RestControllerAdvice with logging using ServerWebExchange
- src/main/kotlin/.../config/OpenApiConfig.kt — OpenAPI title/description/version
- src/main/resources/application.yaml — management endpoints (health, info,
  prometheus), Kubernetes probe groups enabled, health details shown

**Summary**

Error handling was restructured into the team's coherent style (typed exception
base + centralized advice + logging) but the response body was deliberately kept
as the API contract's {"error":"product_not_found"}, NOT switched to RFC 7807
ProblemDetail — a ProblemDetail switch would be an API Contract change requiring
team approval. Swagger UI, the OpenAPI JSON and the actuator endpoints were all
verified working at runtime by the human.

**Potential Risks / Assumptions**

- First attempt used springdoc 2.8.x, which targets Spring Boot 3.x: the OpenAPI
  JSON generated but the Swagger UI would not serve on Spring Boot 4 / Framework
  7. Fixed by moving to the springdoc 3.x line (3.0.3), the Boot 4 branch. Worth
  an ai-mistake-log.md entry.
- springdoc 3.x on Boot 4 is very new; keep an eye on it as the app evolves.

**Confidence**

High — build and tests pass, and the human confirmed Swagger UI, /v3/api-docs
and /actuator/{health,prometheus} all respond at runtime.

**Notes**

Actuator is per-service (each microservice exposes its own /actuator/*). Still
outstanding for full deploy-readiness (config repo): the catalog-db CloudNativePG
manifest + secret, and reconciling architecture-design.md (still says "no DB").

---

### 2026-07-08 22:04

**Agent**

Claude (Opus 4.8) via Claude Code

**Task**

Implement the Catalog service (Phase 1, task 1): `GET /api/v1/catalog/products`
(list) and `GET /api/v1/catalog/products/{train_id}` (single, 404 when missing),
backed by its own PostgreSQL database.

**Files Modified / Created (catalog/)**

- build.gradle.kts — added spring-boot-starter-data-r2dbc + r2dbc-postgresql
- src/main/resources/application.yaml — R2DBC config via env vars, spring.sql.init
- src/main/resources/schema.sql, data.sql — schema + idempotent seed
- src/main/kotlin/.../entities/ — ProductEntity, SeatClassEntity
- src/main/kotlin/.../repositories/ — ProductRepository, SeatClassRepository
- src/main/kotlin/.../dto/CatalogDtos.kt — request/response DTOs (snake_case)
- src/main/kotlin/.../service/ — CatalogService (interface) + DefaultCatalogService
- src/main/kotlin/.../controllers/CatalogController.kt
- src/main/kotlin/.../exceptions/CatalogExceptionHandler.kt (+ ProductNotFoundException)
- src/test/kotlin/.../CatalogControllerTest.kt — standalone WebTestClient tests
- src/test/kotlin/.../CatalogApplicationTests.kt — fixed wrong package (inventory -> catalog)

**Summary**

Reactive WebFlux service with Kotlin coroutines. Persistence via Spring Data
R2DBC (not JPA, which is blocking and would break the reactive stack): two
tables (products, seat_classes) assembled into the contract's nested response in
the service layer. Snake_case wire keys pinned with @JsonProperty (including the
reserved "class" key). 404 returns {"error":"product_not_found"} via a
@RestControllerAdvice. Seed loaded idempotently by spring.sql.init. Package
layout kept flat/unGrouped by role: entities, repositories, controllers,
exceptions, dto, service.

**Verification**

- ./gradlew clean test: 3 controller tests pass, 1 context-load test skipped
  (deferred to Phase 2 Testcontainers).
- End-to-end run against a throwaway Dockerised PostgreSQL: list, single, and
  404 responses all correct; column mapping, TIMESTAMPTZ->Instant and
  NUMERIC->BigDecimal verified. Environment torn down afterwards.

**Potential Risks / Assumptions**

- Catalog owning a database was a human-approved decision. The pushed
  architecture-design.md currently still says "Catalog: no DB", so code and doc
  diverge until the doc is reconciled (config repo, out of scope here).
- Infra not yet created: catalog-db CloudNativePG cluster manifest and the
  SPRING_R2DBC_* secret/env for deployment.
- Prices serialize as JSON numbers (e.g. 25.0), numerically equal to the
  contract's 25.00.

**Confidence**

High — compiles, unit tests and a real DB-backed end-to-end run all pass.

**Notes**

Naming inconsistency across project docs: ai-guidelines.md §16 uses ai-logs.md
(this file), while dod.md / the architecture app-repo structure reference
agent-log.md. Team should converge on one name.
