# AI Interaction Log — Application Repo

This file records significant AI-assisted development sessions in the
application repo, as required by `ai-guidelines.md` §16. Newest entries first.

---

### 2026-07-13 13:30

**Agent**

Claude Opus 4.8

**Task**

Phase 2 (money path), Inventory & Payments: Kafka consumer dedup for
`order-failed`, request-level dedup for the two internal synchronous calls, and
validation of the consistency guarantees under concurrent load.

**Files Modified**

Application repo (two branches):

- `backend/inventory/src/main/resources/schema.sql` (+ `processed_requests`, `processed_events`)
- `backend/inventory/.../entities/{ProcessedRequestEntity,ProcessedEventEntity}.kt`
- `backend/inventory/.../repositories/{ProcessedRequestRepository,ProcessedEventRepository}.kt`
- `backend/inventory/.../service/DefaultInventoryService.kt`
- `backend/inventory/src/test/.../InventoryReserveTest.kt`, `OrderFailedConsumerDedupTest.kt` (new)
- `backend/inventory/CLAUDE.md`
- `backend/payments/src/main/resources/schema.sql` (+ `processed_requests`)
- `backend/payments/.../entities/ProcessedRequestEntity.kt`, `.../repositories/ProcessedRequestRepository.kt`
- `backend/payments/.../service/{DefaultPaymentsService,DecisionRecorder}.kt`
- `backend/payments/.../gateway/{PaymentGateway,HttpPaymentGateway}.kt` (extracted the `circuit_breaker_open` constant)
- `backend/payments/src/test/.../PaymentsIdempotencyTest.kt` (new), `PaymentsAuthorizeTest.kt`
- `backend/payments/CLAUDE.md`

Configuration repo:

- `docs/consistency-validation.md` (new), `docs/dod.md`

**Summary**

Implemented idempotency levels 2 and 3 from contract §3.2. The starting premise
of the task was wrong in both directions and this was caught by reading the code
before implementing: the `order-failed` consumer and the 10-on-5 concurrency test
already existed, while `processed_requests` — believed to be delivered in Phase 1
— existed in neither Inventory nor Payments (both carried an explicit "out of
scope" comment saying so).

Inventory: `processed_requests` claim is taken *before* the seat decrement, so a
concurrent duplicate cannot get past it (the loser's `ON CONFLICT DO NOTHING`
blocks on the winner's row lock, then reads back the committed reservation). A
409 rolls the claim back rather than memoising it. `processed_events` gates the
`order-failed` consumer in the same transaction as the compensation.

Payments: the recorded decision is replayed before the gateway is called; the
transaction is opened only afterwards, around the `transactions` row and the
claim together, so an order gets exactly one transaction row however a retry
overlaps. A `circuit_breaker_open` decline is deliberately not memoised.

Tests now drive concurrency over real HTTP (10-on-5 and 50-on-20 mixed
quantities) rather than calling the service bean in-process, and the Kafka
consumer is exercised against a real broker for the first time — the previous
suite set `spring.kafka.listener.auto-startup=false`, leaving it entirely
unverified.

**Potential Risks**

- **Compensation still does not work end-to-end**, for reasons outside these two
  services: Orders publishes `order-failed` to a bare topic name (missing the
  `eurotransit.` prefix) with no `reservation_id` and no `event_id` in the
  payload. Inventory's consumer is correct and tested but nothing reaches it.
  Raised with the Orders owner; the DoD box stays unticked.
- Payments calls the gateway *before* it persists. A pod death in between leaves
  the charge taken with no local row; the ledger self-heals on retry only because
  the gateway sends `Idempotency-Key: order_id` to Stripe. Documented as residual
  risk rather than silently closed.
- The claim-first ordering in Inventory relies on Postgres blocking
  `ON CONFLICT DO NOTHING` on the conflicting row's lock. This is correct under
  READ COMMITTED but is a database-specific guarantee.

**Confidence**

High for Inventory and Payments. Each guard was mutation-tested: with the gate
disabled, exactly the intended tests fail and no others. That also confirmed a
claim made to the reviewer — that `processed_events` is defence in depth rather
than a live bug fix, since the RESERVED→RELEASED status guard alone already
prevents a double release.

**Notes**

No architecture, contract, Kafka topic or API change. Both new tables are
specified in contract §3.2 and already listed for Inventory in
architecture-design.md §2, so no §19 escalation was required. No new dependencies.

---


**Agent**

Claude (Opus 4.8) via Claude Code

**Task**

Add distributed JWT validation to Payments (architecture pattern B) so
`POST /api/v1/payments/authorize` requires a valid Bearer token, AND make Orders'
`PaymentClient` forward a service-account token so Stage 2 keeps working under
enforcement — matching what Inventory + Orders→Inventory already do.

**Files Modified**

- backend/payments/build.gradle.kts (+ oauth2-resource-server, + spring-security-test)
- backend/payments/src/main/kotlin/.../config/SecurityConfig.kt (new)
- backend/payments/src/main/kotlin/.../config/JwtAudienceValidator.kt (new)
- backend/payments/src/main/resources/application.yaml (issuer-uri, audience)
- backend/payments/src/test/kotlin/.../SecurityConfigTest.kt (new)
- backend/payments/src/test/kotlin/.../PaymentsAuthorizeTest.kt (mock decoder + token)
- backend/payments/src/test/kotlin/.../PaymentGatewayCircuitBreakerTest.kt (mock decoder)
- backend/payments/CLAUDE.md
- backend/orders/src/main/kotlin/.../client/PaymentClient.kt (forward service token)
- backend/orders/src/test/kotlin/.../PaymentClientTest.kt (bearer-token test)

**Summary**

Payments: mirrored Inventory's resource-server setup into the payments package.
`SecurityConfig` protects `/api/v1/payments/**` (`authenticated`), keeps actuator
and Swagger open, and validates JWTs locally against Keycloak's JWKS
(`issuer-uri`) with an audience check (`JwtAudienceValidator`,
`app.security.jwt.audience` default `payments`). The `jwtDecoder` bean fetches
JWKS at startup, so every `@SpringBootTest` mocks `ReactiveJwtDecoder`; endpoint
tests send `Bearer test-token` with a stubbed decode returning a Jwt carrying
`aud=payments`.

Orders: `PaymentClient` now applies the same `bearerTokenFilter` as
`InventoryClient`, reusing the existing `ServiceTokenProvider` (one `orders-service`
client-credentials token for both edges, gated by the shared
`app.security.service-token.*` toggle, default off — the config key was renamed
from `...service-token.inventory.*` since it now serves both edges). Added a
`PaymentClientTest` asserting the `Bearer <token>` header reaches `/authorize`.

Built on a fresh branch off `dev` (which already has the security infra merged).
Payments build green (8 tests, 0 skipped); Orders build green (incl. the 2
PaymentClient tests).

**Potential Risks**

- **Keycloak config (required for enforcement):** the `orders-service` token's
  `aud` must contain `payments` (audience mapper / client scope) or Stage 2 401s.
  Infra, not code.
- The service-token config was renamed `app.security.service-token.inventory.*` →
  `app.security.service-token.*` (env `INVENTORY_SERVICE_TOKEN_ENABLED` →
  `SERVICE_TOKEN_ENABLED`) since one orders-service token now serves both the
  Inventory and Payments edges. No config-repo references existed, so the rename
  is self-contained.
- Config repo: `PAYMENTS_JWT_AUDIENCE` / `KEYCLOAK_ISSUER_URI` env wiring in the
  Payments Deployment/values not added here.

**Confidence**

High — both services build with tests green; inbound 401-without-token proven and
the Orders bearer-token forwarding asserted. End-to-end enforcement additionally
depends on the flagged Keycloak audience config.

**Notes**

`dev` already carries the shared security infra (`ServiceTokenProvider`,
`InventoryClient` token filter, oauth2 deps), so this change only added the
Payments resource server and the missing Payments edge on `PaymentClient`.


### 2026-07-11 17:30

**Agent**

Claude (Opus 4.8) via Claude Code

**Task**


Turn `payment-gateway-sim` from a pure local simulator into a real Stripe
adapter, without changing Payments or the `POST /gateway/charge` request/response
contract. Keep the `X-Simulate-*` fault-injection short-circuit intact for chaos
testing, and add a local/CI profile that needs no Stripe credentials.

**Files Modified**

- backend/payment-gateway-sim/src/main/kotlin/.../gateway/ChargeGateway.kt (new)
- backend/payment-gateway-sim/src/main/kotlin/.../gateway/LocalChargeGateway.kt (new)
- backend/payment-gateway-sim/src/main/kotlin/.../gateway/StripeChargeGateway.kt (new)
- backend/payment-gateway-sim/src/main/kotlin/.../gateway/StripeDtos.kt (new)
- backend/payment-gateway-sim/src/main/kotlin/.../config/ChargeGatewayConfig.kt (new)
- backend/payment-gateway-sim/src/main/kotlin/.../controllers/GatewayController.kt
- backend/payment-gateway-sim/src/main/resources/application.yaml
- backend/payment-gateway-sim/src/main/resources/application-local.yaml (new)
- backend/payment-gateway-sim/build.gradle.kts
- backend/payment-gateway-sim/src/test/kotlin/.../GatewaySimTest.kt
- backend/payment-gateway-sim/src/test/kotlin/.../StripeChargeGatewayTest.kt (new)
- backend/payment-gateway-sim/CLAUDE.md (new)

**Summary**

The normal path now calls Stripe's PaymentIntents API for real via a reactive
`WebClient` (chosen over the blocking Stripe Java SDK: non-blocking, zero new
runtime deps, consistent with the repo's other HTTP edges). It creates and
confirms a PaymentIntent in one server-to-server call (`confirm=true`,
`allow_redirects=never`), sends `order_id` as the `Idempotency-Key`, pins
`Stripe-Version`, and applies a `responseTimeout` independent of any circuit
breaker. Outcomes map to the frozen `ChargeResponse`: `succeeded → AUTHORIZED`;
402 / non-success → `DECLINED` with Stripe's `decline_code`; network/5xx/timeout
are rethrown, never turned into a fake decision. `ChargeGateway` abstracts the
decision; `LocalChargeGateway` keeps the amount-threshold synth and backs both
the fault-injection short-circuit and the normal path when Stripe is disabled.
Bean selection is by `app.stripe.enabled` alone (symmetric `@ConditionalOnProperty`),
with qualifier-based injection. `app.stripe.enabled=false` (also
`application-local.yaml`) lets CI/local build/test run with no credentials; the
Stripe mapping is covered by a WireMock-backed test. 9/9 tests pass, 0 skipped.

**Potential Risks**

- The WireMock tests validate the mapping against *assumed* Stripe wire formats,
  not a live call. Real-Stripe risks not yet confirmed: combining
  `automatic_payment_methods` with an explicit `payment_method`; the exact
  decline JSON; the `2024-06-20` API version. Needs one manual run with a test
  key (`STRIPE_SECRET_KEY=sk_test_...`) to validate.
- `ChargeRequest` carries no card token, so every confirm uses one configured
  test payment method (`pm_card_visa`); per-order selection is a follow-up.
- New external dependency + egress to `api.stripe.com`, and a Stripe secret.
  Architecture/contract docs and the service's Helm deployment + SealedSecret are
  not updated here (proposed separately, pending approval / config repo).

**Confidence**

Medium — internal logic and mapping are test-covered and green; adherence to the
real Stripe API is unvalidated until a live test-mode call is made.

**Notes**

Payments is untouched (no file under `backend/payments/`). The `X-Simulate-*`
short-circuit is byte-for-byte unchanged and still skips Stripe entirely. The
adapter fails fast at startup if enabled with a blank key.

---

### 2026-07-10 19:10

**Agent**

Claude (Opus 4.8) via Claude Code

**Task**

Implement the second circuit breaker from contract §1.5 — Payments → external
gateway — and expose the gateway as a separate simulated HTTP service so the
edge is a real network call and the breaker can be exercised (incl. by latency
injection). Two separate commits.

**Files Created / Modified**

- (commit 1) payment-gateway-sim/ — new WebFlux service (no DB, no Kafka):
  GatewayController `POST /gateway/charge` (decline-above rule migrated here +
  X-Simulate-Delay-Ms / X-Simulate-Failure fault injection), DTOs, OpenApiConfig,
  application.yaml, GatewaySimTest (4 tests); justfile registers the module.
- (commit 2) payments/build.gradle.kts — + resilience4j-spring-boot3/kotlin
  2.2.0, wiremock-standalone 3.9.1 (test)
- payments/.../gateway/HttpPaymentGateway.kt — WebClient + programmatic
  resilience4j (CircuitBreakerRegistry.executeSuspendFunction) + fallback →
  circuit_breaker_open; **removed** MockPaymentGateway.kt
- payments/src/main/resources/application.yaml — app.gateway.url/timeout,
  resilience4j.circuitbreaker.payment-gateway, actuator circuitbreakers
- payments/src/test/.../PaymentsAuthorizeTest.kt — reworked to drive decisions
  from a WireMock gateway; PaymentGatewayCircuitBreakerTest.kt — new

**Summary**

The gateway is now a standalone service; Payments calls it over HTTP wrapped in
a COUNT_BASED Resilience4j breaker that opens on failures AND on slow calls
(slow-call-duration-threshold 2s), satisfying the chaos "latency injection"
requirement — not only on exceptions. The WebClient responseTimeout (5s) sits
above the slow-call threshold so slow calls register as slow rather than errors.
On open (or any gateway failure/timeout) the fallback returns the contract's
`circuit_breaker_open` (402), distinguishable from `insufficient_funds`. Chose
the programmatic resilience4j-kotlin API over the annotation for reliable
coroutine + fallback behavior. The 402 fallback reason follows the contract
(§1.5/§2.4), not the initially-suggested PAYMENT_GATEWAY_UNAVAILABLE.

**Potential Risks / Assumptions**

- New service (payment-gateway-sim): architecture-design.md (config repo) should
  be updated to describe the in-cluster gateway simulator (the gateway is
  documented as an opaque external third party) — §19 doc reconciliation.
- Deploy of the simulator (Helm/manifest, config repo) is deferred; it is
  registered in the justfile but NOT in CI. Note: the CI values-update step uses
  `yq .<service>.image.tag`, which breaks on the hyphenated name unless the key
  is quoted — fix when wiring CI/deploy.
- CB threshold values are a deliberate, justified choice (see plan), not library
  defaults; the CB test overrides them with faster values to keep tests quick.
- Tests require Docker (Testcontainers for the transactions DB).

**Confidence**

High — build + 7 payments tests + 4 simulator tests pass; the breaker is shown
opening on both 503 failures and slow calls, with the circuit_breaker_open
fallback.

**Notes**

Same JUnit 6 "must not return a value" pitfall avoided in the WebTestClient
tests (block bodies → Unit).

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

---

### 2026-07-11 13:15

**Agent**

Codex

**Task**

Refactor the application repository layout so backend services live under a
single `backend/` directory.

**Files Modified**

- backend/catalog/** moved from catalog/**
- backend/inventory/** moved from inventory/**
- backend/notifications/** moved from notifications/**
- backend/orders/** moved from orders/**
- backend/payments/** moved from payments/**
- backend/payment-gateway-sim/** moved from payment-gateway-sim/**
- .github/workflows/ci.yaml
- .github/workflows/pr.yaml
- justfile
- README.md
- docs/ai-logs.md

**Summary**

Moved all backend services under `backend/` while keeping each microservice as
an independent Gradle project. Updated GitHub Actions path filters, build/test
working directories, Docker build contexts, and local just commands to use the
new layout. The frontend, docs, and tools directories remain at repository root.

**Potential Risks**

- CI path changes need validation in GitHub Actions after the branch is pushed.
- The configuration repository did not contain application filesystem path
  references in the inspected files, so no config repo changes were made.
- Local commands now expect service names relative to `backend/`.

**Confidence**

Medium — this is a filesystem-only refactor and no package names changed, but
the user explicitly requested not to run tests.

**Notes**

Kubernetes service names, image repository names, API paths, database names, and
Kafka topics were intentionally left unchanged.

---

### 2026-07-11 14:30

**Agent**

Codex

**Task**

Implement Keycloak JWT validation for Orders and Inventory, excluding Payments per human instruction.

**Files Modified**

- backend/orders/build.gradle.kts
- backend/orders/src/main/resources/application.yaml
- backend/orders/src/main/kotlin/it/polito/eurotransit/orders/config/JwtAudienceValidator.kt
- backend/orders/src/main/kotlin/it/polito/eurotransit/orders/config/SecurityConfig.kt
- backend/orders/src/main/kotlin/it/polito/eurotransit/orders/client/ServiceTokenProvider.kt
- backend/orders/src/main/kotlin/it/polito/eurotransit/orders/client/InventoryClient.kt
- backend/orders/src/test/kotlin/it/polito/eurotransit/orders/JwtAudienceValidatorTest.kt
- backend/orders/src/test/kotlin/it/polito/eurotransit/orders/SecurityConfigTest.kt
- backend/orders/src/test/kotlin/it/polito/eurotransit/orders/InventoryClientTest.kt
- backend/inventory/build.gradle.kts
- backend/inventory/src/main/resources/application.yaml
- backend/inventory/src/main/kotlin/it/polito/eurotransit/inventory/config/JwtAudienceValidator.kt
- backend/inventory/src/main/kotlin/it/polito/eurotransit/inventory/config/SecurityConfig.kt
- backend/inventory/src/test/kotlin/it/polito/eurotransit/inventory/JwtAudienceValidatorTest.kt
- backend/inventory/src/test/kotlin/it/polito/eurotransit/inventory/SecurityConfigTest.kt
- backend/inventory/src/test/kotlin/it/polito/eurotransit/inventory/InventoryReserveTest.kt

**Summary**

Orders now validates Keycloak-issued JWTs on `/api/v1/orders/**` with issuer and
audience validation. Inventory now validates Keycloak-issued service-to-service
JWTs on `/reserve`. Orders can obtain a client-credentials token for Inventory
and attaches it as a Bearer token when calling Inventory. Catalog remains public
and Notifications has no public HTTP API to protect in this scope.

**Potential Risks**

- Payments service-to-service JWT is intentionally excluded by human instruction,
  even though the architecture and contract include it as a future requirement.
- Runtime success depends on the Keycloak realm, audiences, and the
  `orders-service-client` Kubernetes Secret matching the configured values.
- Tests were not run by Codex because the human requested to run them locally.

**Confidence**

Medium — implementation follows the documented security model for Orders and
Inventory, but it still needs local and CI verification.

**Notes**

No secrets were committed. Kafka events remain unchanged and do not carry JWTs.
