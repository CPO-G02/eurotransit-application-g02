# payment-gateway-sim service

A standalone WebFlux microservice that fronts the external payment processor for
Payments. Internal service — **not** exposed via Traefik; called synchronously by
Payments at `POST /gateway/charge`.

It ships **two** normal-path backends, selected by `app.stripe.enabled`, plus a
fault-injection short-circuit that bypasses both:

- **`enabled: false` — what the cluster runs today** (`stripe.enabled: false` in
  the config repo's `values.yaml`). `LocalChargeGateway` synthesizes the
  decision: AUTHORIZED at or below `app.gateway.decline-above` (500.00), DECLINED
  above it. No Stripe, no network egress, no credentials.
- **`enabled: true` — off deliberately.** `StripeChargeGateway` calls Stripe's
  PaymentIntents API for real. See "Why Stripe is off" below before turning it on.

## Stack
- Kotlin 2.3 / Spring Boot 4.1 / Java 21, WebFlux + coroutines (`suspend fun`).
- No database, no Kafka. Actuator + springdoc (Swagger UI).
- Talks to Stripe via a reactive `WebClient` (see decision below).

## Source of truth
Architecture and contract live in the **configuration repo**:
`docs/architecture-design.md` and `docs/eurotransit-contract.md`. Precedence and
change rules: `docs/ai-guidelines.md`. Do not change the API/response shape
Payments depends on without approval.

## Contract (frozen — Payments depends on it as-is)
`POST /gateway/charge`
- Request `ChargeRequest { order_id, amount, currency }`.
- Response `ChargeResponse { decision, reason? }` where `decision` is
  `"AUTHORIZED"` or `"DECLINED"` and `reason` is a mapped string on decline.

These field names/types must not change; Payments is not touched by this service.

## Normal path when Stripe is enabled (not the deployed configuration)
`StripeChargeGateway` creates and confirms a PaymentIntent in a single
server-to-server call (`confirm=true`, `automatic_payment_methods.allow_redirects
= never` — no redirect, no webhook) and maps the outcome:
- `status == succeeded` → `AUTHORIZED`.
- HTTP 402 (`card_declined` / `insufficient_funds` / …) or a non-success status →
  `DECLINED`, `reason` = Stripe's `decline_code` (falling back to `code`).
- Any other failure (network, 5xx, timeout) is **rethrown**, never turned into a
  fabricated decision — it's a gateway failure for Payments' own circuit breaker
  (a separate task) to absorb.

`order_id` is sent as Stripe's `Idempotency-Key`, so Orders' bounded retries hit
the same PaymentIntent instead of double-charging.

### Known limitation — single payment method
`ChargeRequest` carries no card/payment-method token, so every confirm uses one
configured test-mode token `app.stripe.payment-method` (default `pm_card_visa`).
Per-order payment-method selection would require extending the contract and is a
**follow-up task**, intentionally not implemented here.

Worth being explicit about the consequence, because the checkout UI implies
otherwise: **no card data reaches this service, or any service.** The frontend
collects a card number and CVC, validates the expiry client-side and shows the
last four digits on the receipt, then discards them — none of the DTOs from
`POST /orders` down to `ChargeRequest` carry a card field. So even with Stripe
enabled, every charge would confirm `pm_card_visa`, which always succeeds no
matter what a user typed. Handling real cards means tokenizing in the browser
(Stripe Elements) and threading a `payment_method` token through the contract.

## Why Stripe is off
`stripe.enabled: false` in the config repo is a decision, not an oversight or a
half-finished migration. Recorded here so it is not "fixed" by someone who reads
`StripeChargeGateway` and assumes it should be live:

- **It is not a requirement.** The capstone brief names no payment provider and
  grades none. The word "Stripe" appears zero times in the assignment and zero
  times in `dod.md`. It earns nothing.
- **It cannot be made slow on demand**, which is the entire point of this edge.
  Chaos experiment 1 ("latency injection into Payments") needs a gateway that can
  be delayed and failed to order — that is what `X-Simulate-*` and
  `LocalChargeGateway` are. Enabling Stripe does not make the experiments more
  real; they would have to bypass it anyway.
- **It adds an external dependency to the live demo.** A slow Stripe or a wrong
  key during the presentation fails the money path for reasons that have nothing
  to do with the system being presented.
- **It would remove the only UI-reachable decline.** `LocalChargeGateway`'s
  500.00 threshold is what lets a demo trigger `payment-failed` → compensation
  from the browser. With Stripe on, `pm_card_visa` always authorizes.

The adapter is kept because it is written, tested and harmless when off — not
because it is pending. Turning it on requires the `payment-gateway-sim-stripe`
Secret to exist first; the app fails fast at startup if enabled without a key.

## Fault-injection short-circuit (kept on purpose)
The `X-Simulate-Delay-Ms` and `X-Simulate-Failure` request headers are a
**deliberate** short-circuit for deterministic chaos experiments and test
harnesses. When either header is present the controller **bypasses the
normal-path backend entirely** — Stripe, when it is enabled — and synthesizes the
response locally (`LocalChargeGateway`, amount-threshold at
`app.gateway.decline-above`), with `X-Simulate-Delay-Ms` adding latency and
`X-Simulate-Failure` returning `503`. Payments never sends these headers in normal
operation.

This path is what makes the gateway edge testable at all, and it is why enabling
Stripe would not make the chaos experiments more faithful: they inject faults
here, above whichever backend is configured.

## Blocking-SDK decision (WebClient over the Stripe Java SDK)
This service is WebFlux + coroutines; the official Stripe Java SDK's HTTP client
is blocking (OkHttp). We call the Stripe REST API directly via a **reactive
WebClient** instead. Rationale: fully non-blocking, zero new runtime dependencies
(WebClient is already on the classpath), and consistent with every other HTTP
edge in the repo — over parking a pooled IO thread per call in a service whose
whole point is latency/fault chaos. Cost we accept: we own form-encoding and error
mapping, and pin `Stripe-Version` so response shapes don't drift.

## Configuration
- `app.stripe.enabled` — application default `true`, but **the cluster sets it to
  `false`** (`stripe.enabled` in the config repo's `values.yaml` → `STRIPE_ENABLED`).
  Read the deployed value there, not this default. `false` backs the normal path
  with `LocalChargeGateway`, so CI/local build/test need no Stripe credentials.
  See "Why Stripe is off".
- `app.stripe.secret-key` ← `STRIPE_SECRET_KEY`, sourced from a Kubernetes Secret
  (SealedSecret / kubeseal, as other services do). Never hardcoded or committed;
  the adapter fails fast at startup if enabled with a blank key.
- `app.stripe.base-url`, `app.stripe.api-version`, `app.stripe.payment-method`,
  `app.stripe.timeout-ms` (WebClient responseTimeout, independent of any circuit
  breaker), `app.gateway.decline-above` (local synth threshold).

## Bean wiring
`LocalChargeGateway` is always a `@Component` (used directly by the fault path).
The normal-path `ChargeGateway` bean (`@Qualifier("chargeGateway")`) is selected
by `app.stripe.enabled` alone: `StripeChargeGateway` when `true`, and a
`@Configuration` `@Bean` exposing `LocalChargeGateway` when `false`. The two
conditions are mutually exclusive by property value — no reliance on
bean-registration order.

## Explicitly out of scope (other tasks)
- Payments' own circuit breaker on the Payments→gateway edge.
- `processed_requests` idempotency in Payments.
- Helm deployment + the actual SealedSecret for this service (config repo).
