# AI Interaction Log — Application Repo

This file records significant AI-assisted development sessions in the
application repo, as required by `ai-guidelines.md` §16. Newest entries first.

---

### 2026-07-16 13:20

**Agent**

Claude Sonnet 5

**Task**

Chase the Orders→Inventory/Payments 401s and outbox/Kafka bugs through to a
real end-to-end confirmed order, fixing config as each new layer surfaced.

**Files Modified**

- `platform/keycloak/keycloak-cr.yaml`
- `platform/observability/kube-prometheus-stack-values.yaml`

**Summary**

`keycloak-cr.yaml`'s committed `hostname.hostname` was a full `https://...`
URL, which drops `http-relative-path` and breaks the issuer's `/auth` prefix —
a regression from an earlier live-only fix that was never actually committed
(sitting in an old, unpopped `git stash`). Reverted to a bare hostname and
added `hostname-backchannel-dynamic: false` via `additionalOptions`, though
live testing showed this alone doesn't fix the backchannel issuer scheme (see
Notes). Also landed the already-verified-live `kubeApiServer: enabled: false`
and Prometheus memory (340Mi) trim from the same stash.

Separately, live (not git-tracked): recreated the `payments` client and
`payments-audience` client-scope in the realm via the Admin REST API and
attached it to `orders-service` — `realm-import.yaml` already specified both
correctly, but that resource is a one-shot `KeycloakRealmImport` the operator
only runs once at creation, so a later edit to the file never reconciles into
an already-imported realm. Also disabled `chaos-mesh`'s ArgoCD `automated`
sync (live only) and scaled it to 0/0 per explicit request to stop it fighting
manual scale-downs.

**Verification**

- Decoded real service-account JWTs from both the public and internal token
  endpoints before/after each change to confirm `iss` and `aud` directly,
  rather than trusting the operator's reconciliation status.
- Watched a real order reach `CONFIRMED` end to end after all pieces landed.

**Potential Risks**

- The `payments` client/scope and the chaos-mesh selfHeal disable are live-only
  changes with no git record. `realm-import.yaml` already matches the realm
  fix (no action needed there), but `chaos-mesh-application.yaml` still has
  `selfHeal: true` in git — if that file is ever re-applied, chaos-mesh reverts
  to fighting manual scaling again.
- The actual backchannel-issuer-scheme fix ended up living in the application
  repo (`ServiceTokenProvider` sending `X-Forwarded-*` headers on its own
  token request), not here — `hostname-backchannel-dynamic: false` alone was
  verified insufficient live.
- Keycloak operator still hits the same 409-conflict/retry-exhaustion issue
  from earlier sessions when reconciling the CR; the `keycloak-cr.yaml` change
  was applied via direct `kubectl` env patch + operator scaled back to 0, same
  as the established workaround, not via a clean operator reconcile.

**Confidence**

High for the parts verified against real decoded tokens and a real confirmed
order. Low for whether the operator would apply `keycloak-cr.yaml` cleanly on
its own if reconciliation were re-attempted — never actually tested.

**Notes**

Also traced (not fixed here): CI's `ACR_LOGIN_SERVER`/`ACR_USERNAME`/
`ACR_PASSWORD` GitHub secrets on the application repo were pointing at an
unrelated personal registry, which is why earlier pushes looked like they
weren't taking effect on the cluster. User corrected the secrets directly;
no change needed in this repo.

---

### 2026-07-16 6:00

**Agent**

Claude Sonnet 5

**Task**

Get a real order through the full Orders saga (Inventory → Payments →
Notifications) end to end, fixing every bug found live along the way.

**Files Modified**

- `backend/orders/src/test/kotlin/.../SagaRecoveryTest.kt`
- `backend/orders/src/main/kotlin/.../client/ServiceTokenProvider.kt`
- `backend/orders/src/test/kotlin/.../InventoryClientTest.kt`
- `backend/orders/src/test/kotlin/.../PaymentClientTest.kt`
- `backend/notifications/src/main/resources/application.yaml`
- `backend/notifications/src/main/kotlin/.../config/KafkaConfig.kt` (new)
- `backend/notifications/src/test/kotlin/.../KafkaConfigTest.kt` (new)
- `.github/workflows/ci.yaml`

**Summary**

`SagaRecoveryTest` was stale — it still asserted the old buggy
`OutboxRepository.save()` call and built its `OutboxEntry` fixture with no
`id`, so once `OutboxProcessor` was fixed to call `markSent(id, ...)` it threw
`IllegalArgumentException` on the null id and failed CI. Fixed the fixture and
assertion.

`ServiceTokenProvider`'s in-cluster `client_credentials` token request hit
Keycloak directly with no reverse proxy in front, so Keycloak minted
`iss: http://<host>:8080/...` instead of the public `https://.../auth/...`
issuer every resource server validates against — Inventory and Payments both
rejected the token as invalid-issuer (401). Fixed by sending
`X-Forwarded-Proto/Host/Port` on that request ourselves, derived from the
existing `issuer-uri` config, since Keycloak trusts forwarded headers
unconditionally (`proxy: xforwarded`).

Notifications' `value-deserializer: JsonDeserializer` had no default type to
resolve (Orders' outbox doesn't know Notifications' DTO classes), so it
deserialized every message to a plain `LinkedHashMap` with no converter to
bridge that into the listener's typed parameter — every `order-confirmed`/
`order-failed` message was silently dropped after exhausting retries. Fixed by
switching to `StringDeserializer` + a `StringJacksonJsonMessageConverter` bean
(Jackson-3-based — this module depends on `tools.jackson.module:
jackson-module-kotlin`, not classic Jackson 2, which failed first with
`InvalidDefinitionException` on the Kotlin data classes).

Also fixed `ci.yaml`'s GitOps step: it was setting `image.digest` unconditionally
for every service except `notifications`, reintroducing the exact canary-vs-
standard bug already fixed once — pinning `orders` to a digest while
`deploymentStrategies.orders` was `standard`. Now gated on
`deploymentStrategies.<service>`/`blueGreen.<service>.enabled`.

**Verification**

- `./gradlew test` (orders, en-US locale) — 45/45 passed.
- `./gradlew build` (notifications) — both tests passed, including a new
  `KafkaConfigTest` that reproduces the exact wire shape (raw JSON string in,
  typed Kotlin data class out) and was run failing-then-passing to prove the
  fix, not just asserted.
- Live: placed real orders through the browser after each fix landed;
  confirmed `ord-97920218` reached `CONFIRMED` and `ord-762da324` produced an
  actual `Sending confirmation email to ...` log line in Notifications.

**Potential Risks**

- The `X-Forwarded-*` header trust is unauthenticated by design
  (`proxy: xforwarded`) — fine inside the cluster's trust boundary, but not a
  pattern to extend to anything reachable from outside it.
- Three orders placed earlier tonight (`ord-91fad260`, `ord-c8880949`,
  `ord-9ba1e08b`) are permanently stuck `PENDING`: the pre-fix consumer's
  default error handler skipped past their Kafka offsets after exhausting
  retries, so the fixed consumer never sees them again. Left as-is; a fresh
  order was used to prove the fix instead of manually replaying these.
- Catalog lists trains across ~July 10–22, but Inventory's seed data only
  covers 2026-07-10 — any booking for a later date fails with "insufficient
  seats" regardless of real availability. Not touched this session; flagged
  as a separate pre-existing bug.

**Confidence**

High — every fix was verified against the actual failure mode (a real 401, a
real dropped Kafka message, a real CI failure) before and after, not just
inferred from reading the code.

**Notes**

The registry mismatch that made the first two rounds of this fix look like
they weren't taking effect was actually a wrong `ACR_LOGIN_SERVER`/
`ACR_USERNAME`/`ACR_PASSWORD` GitHub secret pointing at an unrelated personal
registry — not a bug in this repo's code or workflow. User corrected the
secrets directly in GitHub; no repo change was needed for that part.

---

### 2026-07-15 21:55

**Agent**

Codex GPT-5

**Task**

Implement the application side of the DoD requirement "Backpressure / load
shedding: HTTP 429 when overloaded" for Orders.

**Files Modified**

- `backend/orders/src/main/kotlin/.../config/OrdersBackpressureConfig.kt`
- `backend/orders/src/main/resources/application.yaml`
- `backend/orders/src/test/kotlin/.../OrdersBackpressureConfigTest.kt`
- `tools/k6/orders-load-shedding.js`
- `docs/ai-logs.md`

**Summary**

Added an inbound WebFlux load-shedding filter for `POST /api/v1/orders`. The
filter is controlled by `app.backpressure.orders.*`, uses a local semaphore, and
returns `HTTP 429 Too Many Requests` with `Retry-After` when the concurrent
order-create limit is exhausted. Status reads, Actuator probes, and downstream
client calls are intentionally outside the filter.

The default limit is `20`, chosen as an initial bound around the existing Orders
R2DBC pool shape (`max-size: 10`) with small burst headroom. The companion
configuration-repo change renders the same policy through
`orders.springApplicationJson` so Argo CD owns the runtime value.

Added a k6 script for authenticated `POST /api/v1/orders` load. It requires
`AUTH_TOKEN` from the environment and does not fetch or store credentials.

**Validation**

Focused unit tests cover the 429 path and verify non-create order requests bypass
the filter. Full validation requires running the Orders test suite and then
observing `429` behavior under controlled live load. A Dockerized k6 runtime was
pulled and the script was executed without `AUTH_TOKEN`; it correctly failed fast
instead of using embedded credentials. The authenticated live load run still
requires an externally supplied Orders-audience token.

**Potential Risks**

The value is a starting threshold, not a tuned capacity limit. It must be adjusted
from live latency, 429 rate, accepted-order completion, CPU throttling, and DB
pool saturation evidence.

---

### 2026-07-15 18:10

**Agent**

Claude Opus 4.8

**Task**

Fix the Orders downstream resilience gap exposed by config PR #22's chaos review:
`InventoryClient` and `PaymentClient` had `@CircuitBreaker` but no HTTP response
timeout, so a hung/partitioned downstream left the coroutine suspended forever
and the breaker never recorded an outcome — it could never open.

**Files Modified**

- `backend/orders/src/main/kotlin/.../client/InventoryClient.kt`
- `backend/orders/src/main/kotlin/.../client/PaymentClient.kt`
- `backend/orders/src/main/kotlin/.../config/WebClientConfig.kt`
- `backend/orders/src/main/resources/application.yaml`
- `backend/orders/src/test/kotlin/.../InventoryClientTest.kt`
- `backend/orders/src/test/kotlin/.../PaymentClientTest.kt`
- `backend/orders/src/test/kotlin/.../OrdersClientCircuitBreakerTest.kt` (new)

**Summary**

Added a Reactor Netty `responseTimeout` to both WebClients (inventory 2s,
payments 6s — the latter exceeds Payments' own 5s gateway timeout) fed by new
`app.inventory.timeout` / `app.payments.timeout` keys. Removed the inert
`resilience4j.timelimiter` block (no `@TimeLimiter` ever wired it) and added
`minimum-number-of-calls: 5` to both circuit-breaker instances.

Switched both clients from the `@CircuitBreaker` annotation to the **programmatic**
`circuitBreakerRegistry.circuitBreaker(name).executeSuspendFunction { ... }` used
by payments' `HttpPaymentGateway`. Discovered while designing the fix that the
annotation was completely inert: no `aspectjweaver` on the runtime classpath, and
even with it the resilience4j 2.2.0 aspect records success the moment a suspend
function suspends. Instance names (`inventory-client`, `payments-client`) are
unchanged, so the yaml/GitOps config still binds.

Also fixed the Orders→Payments 402 handling (mistake-log entry #4): a genuine
card decline is now caught inside the breaker block, its real `reason` propagated,
and counted as a successful call — so a burst of declines can no longer trip the
breaker. The `WebClientConfig` builder bean is now prototype-scoped (it was a
mutated singleton shared by both clients).

**Validation**

- `./gradlew clean test` — full Orders suite green (17 tests, 0 failures).
- New `OrdersClientCircuitBreakerTest`: a hung downstream is recorded as a failure
  and opens the breaker after 5 calls (`numberOfFailedCalls == 5`), the 6th is
  short-circuited (`CallNotPermittedException`); a 402 keeps the breaker CLOSED
  (`numberOfFailedCalls == 0`) with the real reason. This test fails against the
  old annotation approach, which is the proof the programmatic switch was needed.

**Potential Risks**

- Behavior change now that the breaker+fallback are actually active: a Payments
  503/timeout now returns DECLINED → payment-failed (compensation) instead of
  propagating an exception to Stage 2. This is what architecture-design.md §2
  prescribes ("fallback = treat as declined"); the old path was the non-compliant
  one — but it is a real runtime change to call out on deploy.
- `resilience4j.retry` (bounded retry + jitter, wanted by the architecture on
  these edges) is still inert — out of scope here, a tracked follow-up.

**Confidence**

High for the timeout + programmatic-breaker fix and its tests. Medium on the
retry gap remaining, which is deliberately deferred.

**Notes**

Companion config PR removes the mirrored inert `timelimiter` block from
`orders.springApplicationJson` and adds the two `app.*.timeout` keys. Also
surfaced (out of scope): the four `kafka/Stage*ConsumerTest` classes compile but
are never discovered/run by Gradle — pre-existing, identical to `dev`.

---

### 2026-07-14 19:30

**Agent**

Claude Opus 4.8

**Task**

Make `payment-gateway-sim` deployable — CI half. The service was registered in
the justfile but excluded from CI's paths filter, so no image was ever built or
pushed. That is the root reason it has never been deployable, and it had been
flagged and left open since the 2026-07-10 entry.

**Files Modified**

- `.github/workflows/ci.yaml`

**Summary**

Added `payment-gateway-sim: 'backend/payment-gateway-sim/**'` to the
`dorny/paths-filter` list. The existing matrix, ACR push
(`eurotransit/payment-gateway-sim:latest` — slash form, per the naming that
already caused one outage when hyphenated) and the `restartedAt` rollout bump
then apply to it like any other service.

Also quoted the yq key in the rollout step (`."<service>".restartedAt`) for the
dashed service name.

**Potential Risks**

- The config repo must expose a values key named exactly `payment-gateway-sim`
  (dashed), since CI bumps `restartedAt` by directory name. The companion config
  PR renames it from `paymentGatewaySim`; without that rename, image pushes would
  silently never roll this service's pods.
- Merge order matters: this PR must land **before** the chart PR, otherwise the
  chart deploys an image that does not exist in ACR (`ImagePullBackOff`).

**Confidence**

High — workflow YAML parses, and the yq expression was verified empirically
against both a dashed and a plain key.

**Notes**

An earlier ai-log predicted the dashed name would break the unquoted yq
expression. Tested against yq v4: it does **not** break — the unquoted form
resolves the dashed key fine. The quoting was kept anyway as version-drift
insurance, but the recorded prediction was wrong and is corrected here.

The 401 that prompted this task does not come from this service: it has no
security dependency at all (no spring-security, no SecurityConfig), and Payments
calls it with no token. The 401 is Payments rejecting calls that lack a JWT with
`aud=payments` — tracked separately as a Keycloak realm task.

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

---

### 2026-07-14 22:52

**Agent**

Codex (GPT-5)

**Task**

Prepare application CI for immutable-digest progressive delivery.

**Files Modified**

- `.github/workflows/ci.yaml`
- `docs/ai-logs.md`

**Summary**

Created `feature/canary` from the latest remote `dev`. Added Frontend change
detection, conditional React/Vite lint and build gates, container builds on
validation branches, main-only ACR publishing, strict registry digest
verification, propagation of the digest into configuration `main`, and a bounded
Git retry that fails after five unsuccessful pushes. The workflow remains
compatible until `feature/frontend-updates` is merged.

**Potential Risks**

- The workflow has not run on GitHub yet, so repository secrets, runner tooling
  and cross-repository write permission still require CI proof.
- `actionlint` was not executed because downloading the tool was not authorized;
  local YAML parsing and invariant checks passed.
- The React branch has no test script; CI runs its declared lint and build
  scripts without inventing a test command.

**Confidence**

High for branch conditions, digest propagation and compatibility with the
inspected React branch. Medium until the first GitHub-hosted workflow run passes.

**Notes**

No image was pushed, no configuration repository was updated remotely, and no
cluster-changing command was run.

---

### 2026-07-15 16:31

**Agent**

Codex

**Task**

Fix Orders saga persistence, Kafka contract alignment, Jackson serialization, and payment-decline handling after reviewing the AI guidelines and architecture docs.

**Files Modified**

- backend/orders/src/main/kotlin/it/polito/eurotransit/orders/repositories/Repositories.kt
- backend/orders/src/main/kotlin/it/polito/eurotransit/orders/service/OrderServiceImpl.kt
- backend/orders/src/main/kotlin/it/polito/eurotransit/orders/dto/OrderPlacedEvent.kt
- backend/orders/src/main/kotlin/it/polito/eurotransit/orders/client/PaymentClient.kt
- backend/orders/src/main/kotlin/it/polito/eurotransit/orders/kafka/Stage1Consumer.kt
- backend/orders/src/main/kotlin/it/polito/eurotransit/orders/kafka/Stage2Consumer.kt
- backend/orders/src/main/kotlin/it/polito/eurotransit/orders/kafka/Stage3Consumer.kt
- backend/orders/src/main/kotlin/it/polito/eurotransit/orders/kafka/Stage4Consumer.kt
- backend/orders/src/main/kotlin/it/polito/eurotransit/orders/config/JacksonConfig.kt
- backend/orders/src/test/kotlin/it/polito/eurotransit/orders/PaymentClientTest.kt
- backend/orders/src/test/kotlin/it/polito/eurotransit/orders/SagaIntegrationTest.kt
- backend/orders/src/test/kotlin/it/polito/eurotransit/orders/Stage1ConsumerTest.kt
- backend/orders/src/test/kotlin/it/polito/eurotransit/orders/Stage2ConsumerTest.kt
- backend/orders/src/test/kotlin/it/polito/eurotransit/orders/Stage3ConsumerTest.kt
- backend/orders/src/test/kotlin/it/polito/eurotransit/orders/Stage4ConsumerTest.kt
- docs/ai-logs.md

**Summary**

Replaced `save()`-based inserts for String-keyed Orders entities with explicit SQL inserts and `ON CONFLICT DO NOTHING` idempotency claims. Removed the hand-built Jackson `ObjectMapper` bean so Boot's configured snake_case mapper is used, and annotated `OrderPlacedEvent` with contract wire names. Updated all four Orders saga consumers to claim `event_id` atomically, publish configured `eurotransit.*` topics, include contract fields such as `event_id`, `event_timestamp`, `reservation_id`, `user_email`, and payment details, and propagate payment failure reasons to compensation. Updated `PaymentClient` so HTTP 402 is treated as a valid declined business response instead of a circuit-breaker failure.

**Potential Risks**

- Unit tests now assert the contract payload shape, but the Orders repository insert behavior still deserves a real Postgres/R2DBC integration test in CI.
- The existing uncommitted Inventory retry and randomized-wait changes were preserved and not authored in this session.

**Confidence**

High — implementation follows the architecture and contract documents, and the Orders test suite passes locally.

**Notes**

No new topics, dependencies, services, or schema tables were introduced.

---

### 2026-07-15 16:47

**Agent**

Codex

**Task**

Review and verify the recent Orders fixes against `ai-mistake-log.md`, adding only focused tests and minimal correctness fixes.

**Files Modified**

- backend/orders/build.gradle.kts
- backend/orders/src/main/kotlin/it/polito/eurotransit/orders/client/PaymentClient.kt
- backend/orders/src/test/kotlin/it/polito/eurotransit/orders/OrdersRepositoryPostgresTest.kt
- backend/orders/src/test/kotlin/it/polito/eurotransit/orders/client/PaymentClientResilienceTest.kt
- backend/orders/src/test/kotlin/it/polito/eurotransit/orders/OrdersCompensationPathTest.kt
- backend/orders/src/test/kotlin/it/polito/eurotransit/orders/SagaIntegrationTest.kt
- backend/orders/src/test/kotlin/it/polito/eurotransit/orders/Stage1ConsumerTest.kt
- backend/orders/src/test/kotlin/it/polito/eurotransit/orders/Stage2ConsumerTest.kt
- backend/orders/src/test/kotlin/it/polito/eurotransit/orders/Stage3ConsumerTest.kt
- backend/orders/src/test/kotlin/it/polito/eurotransit/orders/Stage4ConsumerTest.kt
- docs/ai-logs.md

**Summary**

Added PostgreSQL Testcontainers coverage for Orders repository inserts and idempotency claims, stricter saga event contract assertions, a focused compensation propagation test, and PaymentClient resilience tests for 200, 402, 5xx, connection reset, timeout, and malformed 402 bodies. Replaced the fragile annotation-only PaymentClient circuit breaker path with the Resilience4j Kotlin `CircuitBreakerRegistry` API so suspend calls are actually protected, while preserving 402 as a business decline.

**Potential Risks**

- In this local environment Docker is unavailable, so `OrdersRepositoryPostgresTest` is skipped by Testcontainers. The test is present and should run in CI or any Docker-enabled developer environment.
- The existing uncommitted Inventory retry and randomized-wait changes were preserved and not authored in this session.

**Confidence**

Medium — non-DB tests pass and the real-DB test is implemented, but full persistence verification still needs a Docker-enabled run.

**Notes**

No Kafka topics, database tables, or service boundaries were changed.

---

### 2026-07-15 15:57

**Agent**

Codex

**Task**

Re-run full Orders verification with Docker available and correct any regressions exposed by the Docker-backed test run.

**Files Modified**

- backend/orders/src/main/kotlin/it/polito/eurotransit/orders/config/ObjectMapperConfig.kt
- backend/orders/src/main/kotlin/it/polito/eurotransit/orders/client/PaymentClient.kt
- backend/orders/src/test/kotlin/it/polito/eurotransit/orders/client/PaymentClientResilienceTest.kt
- backend/orders/src/test/kotlin/it/polito/eurotransit/orders/Stage1ConsumerTest.kt
- backend/orders/src/test/kotlin/it/polito/eurotransit/orders/Stage2ConsumerTest.kt
- backend/orders/src/test/kotlin/it/polito/eurotransit/orders/Stage3ConsumerTest.kt
- backend/orders/src/test/kotlin/it/polito/eurotransit/orders/Stage4ConsumerTest.kt
- docs/ai-logs.md

**Summary**

Docker-backed `OrdersRepositoryPostgresTest` started a real `postgres:16-alpine` container and executed all six repository tests. The first run exposed that Boot 4 did not create a `com.fasterxml.jackson.databind.ObjectMapper` bean for the Orders context after deleting the old config, so a single snake_case `ObjectMapperConfig` bean was added. Full-suite execution also exposed coroutine test methods whose inferred return types caused JUnit discovery warnings; those tests now return `Unit`. PaymentClient cancellation handling was tightened so caller cancellation is rethrown instead of converted into a payment failure, with coverage for open circuit and cancellation.

**Potential Risks**

- `ObjectMapperConfig` explicitly configures `SNAKE_CASE` to match `application.yaml`; if the project changes the naming strategy later, this bean must be kept in sync.
- The existing uncommitted Inventory retry and randomized-wait changes were preserved and not authored in this session.

**Confidence**

High — `./gradlew clean test` executed 34 tests with 0 skipped, 0 failed, and `./gradlew check` passed.

**Notes**

No Kafka topics, database tables, or service boundaries were changed.

---

### 2026-07-15 17:31

**Agent**

Codex

**Task**

Complete final Orders merge checks, verify resilience behavior, and commit the branch.

**Files Modified**

- backend/orders/build.gradle.kts
- backend/orders/src/main/kotlin/it/polito/eurotransit/orders/client/InventoryClient.kt
- backend/orders/src/main/kotlin/it/polito/eurotransit/orders/client/PaymentClient.kt
- backend/orders/src/main/kotlin/it/polito/eurotransit/orders/config/ObjectMapperConfig.kt
- backend/orders/src/main/kotlin/it/polito/eurotransit/orders/repositories/OutboxRepository.kt
- backend/orders/src/test/kotlin/it/polito/eurotransit/orders/OrdersRepositoryPostgresTest.kt
- backend/orders/src/test/kotlin/it/polito/eurotransit/orders/OrdersStage2RollbackPostgresTest.kt
- backend/orders/src/test/kotlin/it/polito/eurotransit/orders/client/InventoryClientResilienceTest.kt
- backend/orders/src/test/kotlin/it/polito/eurotransit/orders/client/PaymentClientResilienceTest.kt
- docs/ai-logs.md

**Summary**

Replaced the interim bare `jacksonObjectMapper()` bean with a Jackson 2 mapper built through `Jackson2ObjectMapperBuilder`, applying Boot `JacksonProperties`, Kotlin module discovery, Java Time support, snake_case, and ISO date serialization. Added Docker-backed tests proving mapper behavior and real PostgreSQL transaction rollback for Stage 2 when outbox insertion fails. Fixed new outbox writes to use explicit SQL with `CAST(:payload AS jsonb)`.

Added transport-level WebClient timeouts for Orders to Inventory and Payments using Reactor Netty `responseTimeout` and connect timeout, reusing the existing `resilience4j.timelimiter.instances.*.timeout-duration` values. Payment HTTP 402 remains a business `DECLINED` response, while timeout/network failures are observable by the circuit breaker and use the infrastructure fallback. Added focused resilience tests for Inventory and Payment clients, including breaker state transition coverage.

Final Retry/CircuitBreaker interaction check for Inventory showed that one logical request currently produces one actual HTTP call, one circuit breaker failure, and lasts about one configured response timeout. The existing `@Retry` annotation did not produce three attempts for the suspend function under the verified Spring wiring.

**Verification**

- `cd backend/orders && ./gradlew clean test` passed.
- `cd backend/orders && ./gradlew check` passed.
- Final totals: 41 tests passed, 0 failed, 0 skipped.
- Commit created: `4a148c4 Fix orders saga resilience and contracts`.

**Potential Risks**

- `Jackson2ObjectMapperBuilder` is deprecated in Spring Boot 4, but this bridge is currently required because Orders production code and Spring Kafka serializers still use Jackson 2 (`com.fasterxml.jackson.databind.ObjectMapper`) while Boot 4's main Jackson starter uses Jackson 3.
- Inventory retry settings are present in configuration, but the verified suspend-function path did not perform multiple attempts. If three physical attempts are required, retry should be wired programmatically rather than relying on the annotation.

**Confidence**

High — full Orders tests and `check` passed after Docker-backed persistence, mapper, rollback, outbox JSONB, and client resilience coverage.

# 2026-07-16 - Circuit-breaker live-check k6 scripts

Added two k6 scripts for the remaining live resilience checks:

- `tools/k6/orders-payments-circuit-breaker.js` generates authenticated
  `POST /api/v1/orders` traffic while the Orders -> Payments chaos experiment is
  active.
- `tools/k6/payments-gateway-circuit-breaker.js` generates authenticated
  `POST /api/v1/payments/authorize` traffic, intended to run through a local
  port-forward to Payments because Payments is not exposed by the public Ingress.

Both scripts require a bearer token supplied outside Git. They intentionally do
not fetch, embed, or store credentials.
