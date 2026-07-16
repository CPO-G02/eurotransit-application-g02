# EuroTransit demo traffic

These PowerShell 7 scripts generate controlled HTTP traffic and evidence files.
They do not promote, abort, retry, patch, or synchronize a Rollout and do not
start a port-forward. The operator owns any `kubectl port-forward` process.
Notifications is deliberately outside the demo scope.

Every worker emits one structured `EuroTransit.TrafficSummary` object and writes
request-level CSV plus a JSON summary under `demo/traffic/results/`. Tokens are
used only as HTTP headers and are never included in either output format.

## Load profiles

Every service entrypoint accepts `-Profile Smoke|Rollout`. Explicit duration,
RPM, timeout, concurrency, and minimum-volume parameters override the profile.

| Profile | Duration | RPM | Timeout | Max concurrency | Minimum generated volume |
| --- | ---: | ---: | ---: | ---: | ---: |
| `Smoke` | 60 seconds | 10 | 3 seconds | 1 | 80% |
| `Rollout` | 25 minutes | 120 | 5 seconds | 5 | 90% |

```powershell
.\demo\traffic\Invoke-CatalogTraffic.ps1 `
  -Target Public `
  -Profile Rollout
```

The generator reuses one asynchronous `HttpClient`, schedules independently of
response completion, and never exceeds `MaxConcurrency`. Request and compact
summary lines use the host stream; the typed `EuroTransit.TrafficSummary`
remains the only success-pipeline object.

Summaries include `expected_requests`, `minimum_required_requests`,
`actual_requests`, and `request_volume_satisfied`. Missing the minimum generated
volume fails the run even when every completed request succeeded.

## Access tokens

Orders, Inventory, and Payments may require different JWT audiences. Use
separate parameters or environment variables:

```powershell
$env:EUROTRANSIT_ORDERS_ACCESS_TOKEN = '<short-lived Orders token>'
$env:EUROTRANSIT_INVENTORY_ACCESS_TOKEN = '<short-lived Inventory token>'
$env:EUROTRANSIT_PAYMENTS_ACCESS_TOKEN = '<short-lived Payments token>'
```

The orchestrator exposes `-OrdersAccessToken`, `-InventoryAccessToken`, and
`-PaymentsAccessToken`. A worker resolves its token in this order:

1. its explicit `-AccessToken` value;
2. `EUROTRANSIT_<SERVICE>_ACCESS_TOKEN`;
3. `EUROTRANSIT_ACCESS_TOKEN`.

The generic variable and orchestrator `-AccessToken` are compatibility
fallbacks only. They work only when that same token is valid for every service
called.

## Destination contract

`Target` determines and validates the real destination.

| Target | Expected destination |
|---|---|
| `Public` | `https://g02.cpo2026.it:443` by default |
| `Stable` / `Active` | `eurotransit-<service>` |
| `Canary` | `eurotransit-<service>-canary` |
| `Preview` | `eurotransit-<service>-preview` |

Public validation compares scheme, host, and port. `example.com`, loopback, and
arbitrary external hosts are rejected by default. Another environment must be
declared explicitly with `-ExpectedPublicHost`, and, when needed,
`-ExpectedPublicScheme` and `-ExpectedPublicPort`. Such a summary reports
`configured-public-route`, never `traefik-public-route`.

For non-public targets, loopback requires the exact
`-PortForwardServiceName`. In-cluster DNS accepts only these forms for the
configured namespace:

```text
eurotransit-catalog
eurotransit-catalog.eurotransit
eurotransit-catalog.eurotransit.svc
eurotransit-catalog.eurotransit.svc.cluster.local
```

`eurotransit-catalog.example.com`, `catalog.example.com`, and a different
service name are rejected. Change `-KubernetesNamespace` explicitly for another
namespace.

Catalog Canary example:

```powershell
kubectl port-forward -n eurotransit svc/eurotransit-catalog-canary 18080:8080

./demo/traffic/Invoke-CatalogTraffic.ps1 `
  -Target Canary `
  -BaseUrl http://127.0.0.1:18080 `
  -PortForwardServiceName eurotransit-catalog-canary `
  -DurationMinutes 5 `
  -RequestsPerMinute 60
```

Only `Target Public` traverses Traefik and its real Canary weights. A direct
`*-canary` or `*-preview` Service intentionally sends all generated traffic to
that candidate.

## Orders money path

Orders defaults to authenticated read-only listing. `MoneyPath` is explicit and
state-changing:

- selects an offer from Catalog, optionally constrained by `-TrainId` and
  `-SeatClass`;
- creates an order with a fresh idempotency key;
- can replay the exact key and payload to validate duplicate identity;
- polls `GET /api/v1/orders/{id}` to a terminal state;
- reports `confirmed`, `failed_insufficient_inventory`,
  `failed_payment_declined`, `failed_technical`, `pending_or_timeout`, and
  unclassified business failures.

Catalog is stale-tolerant and is not the authoritative seat source. An explicit
train/class can therefore be selected even when Catalog reports nominal zero
availability; Inventory decides whether the reservation succeeds.

The real `OrderStatusResponse` has no failure reason or error code. The script
does not invent one. In normal `-ExpectedOutcome Confirmed` runs, a terminal
`FAILED` remains an unclassified business failure and does not invalidate the
deployment test if at least one order is `CONFIRMED` and there are no technical,
timeout, or idempotency failures. Controlled failure tests must explicitly use
`-ExpectedOutcome InsufficientInventory` or `PaymentDeclined`; the summary marks
that classification as operator-expected rather than API-proven.

```powershell
./demo/traffic/Invoke-OrdersTraffic.ps1 `
  -Target Public `
  -CatalogTarget Public `
  -Mode MoneyPath `
  -AcknowledgeMoneyPathSideEffects `
  -AcknowledgeSafePaymentGateway `
  -MaxNewOperations 3 `
  -DuplicatePercentage 25 `
  -RequestsPerMinute 5 `
  -DurationMinutes 5
```

`-AcknowledgeMoneyPathSideEffects` confirms that real orders and reservations
are acceptable. `-AcknowledgeSafePaymentGateway` separately confirms that the
Payments service is configured for a local/test gateway rather than real
Stripe. The script does not inspect or change Payments configuration.

For candidate analysis, `-Profile Rollout` selects the dedicated `Rollout`
write mode unless `-Mode` is explicitly supplied. It creates fresh orders at a
default maximum of 10 RPM for the full 25-minute profile, does not generate
idempotent replays, and considers only successful local HTTP handling plus the
required generated volume. It does not gate on Inventory, Payments, or the
final saga status. The state-changing safety acknowledgements remain mandatory:

```powershell
./demo/traffic/Invoke-OrdersTraffic.ps1 `
  -Target Canary `
  -CatalogTarget Public `
  -Profile Rollout `
  -AcknowledgeMoneyPathSideEffects `
  -AcknowledgeSafePaymentGateway
```

Explicit `-RequestsPerMinute` values are accepted only up to the hard 10 RPM
MoneyPath/Rollout cap. Explicit duration and `-MaxNewOperations` values override
the profile-derived operation budget.

`Run-AllServicesTraffic.ps1 -Profile Rollout` uses this Orders write mode too
when `-TrafficProfile` is omitted. It therefore requires
`-AcknowledgeMoneyPathSideEffects` and `-AcknowledgeSafePaymentGateway`. Pass an
explicit `-TrafficProfile ReadOnly` only when Orders candidate-volume analysis
is intentionally not being fed.

## Direct Inventory and Payments traffic

Inventory and Payments default to readiness-only traffic, with
`application_traffic=false`. Their `Business` modes are direct downstream tests,
not the Orders money path.

Inventory Business reserves seats, requires
`-AcknowledgeSeatConsumption`, and supports exact train/class selection.
HTTP 409 is recorded as expected `insufficient_seats`. Duplicate requests reuse
the same key and payload and must return the same `reservation_id`.

Payments Business requires `-AcknowledgeSafeGatewayConfiguration`. Duplicate
requests must return the same `transaction_id`; HTTP 402 is a business decline,
not a transport failure.

Both business modes are capped at 10 requests per minute and a small operation
count.

## Orchestrator traffic profiles

`Run-AllServicesTraffic.ps1` separates effects explicitly:

| Profile | Orders | Inventory | Payments | Required acknowledgement |
|---|---|---|---|---|
| `ReadOnly` | GET orders | readiness | readiness | none |
| `MoneyPath` | creates complete orders | no direct business calls | no direct business calls | money-path + safe gateway |
| `PerServiceBusiness` | GET orders | direct reservations | direct authorizations | per-service + safe gateway |
| `CombinedExplicit` | complete orders | direct reservations | direct authorizations | all acknowledgements, including combined |

`CombinedExplicit` is never the default and reports
`combined_side_effects=true`. It intentionally duplicates downstream effects
and should be used only when that is the purpose of the experiment.

The payment-gateway simulator worker is optional through
`-IncludeGatewaySimulator`; it is not needed for a normal read-only run.

Example isolated MoneyPath:

```powershell
./demo/traffic/Run-AllServicesTraffic.ps1 `
  -TrafficProfile MoneyPath `
  -AcknowledgeMoneyPathSideEffects `
  -AcknowledgeSafePaymentGateway `
  -OrdersAccessToken $env:EUROTRANSIT_ORDERS_ACCESS_TOKEN `
  -FrontendTarget Public `
  -CatalogTarget Public `
  -OrdersTarget Public `
  -InventoryTarget Active `
  -InventoryBaseUrl http://127.0.0.1:18081 `
  -InventoryPortForwardServiceName eurotransit-inventory `
  -PaymentsTarget Active `
  -PaymentsBaseUrl http://127.0.0.1:18082 `
  -PaymentsPortForwardServiceName eurotransit-payments `
  -DurationMinutes 5
```

Example direct downstream profile:

```powershell
./demo/traffic/Run-AllServicesTraffic.ps1 `
  -TrafficProfile PerServiceBusiness `
  -AcknowledgePerServiceBusinessSideEffects `
  -AcknowledgeSafePaymentGateway `
  -OrdersAccessToken $env:EUROTRANSIT_ORDERS_ACCESS_TOKEN `
  -InventoryAccessToken $env:EUROTRANSIT_INVENTORY_ACCESS_TOKEN `
  -PaymentsAccessToken $env:EUROTRANSIT_PAYMENTS_ACCESS_TOKEN `
  -FrontendTarget Public `
  -CatalogTarget Public `
  -OrdersTarget Public `
  -InventoryTarget Preview `
  -InventoryBaseUrl http://127.0.0.1:18081 `
  -InventoryPortForwardServiceName eurotransit-inventory-preview `
  -PaymentsTarget Preview `
  -PaymentsBaseUrl http://127.0.0.1:18082 `
  -PaymentsPortForwardServiceName eurotransit-payments-preview `
  -DurationMinutes 5
```

Worker success output is accepted only when it is a structured summary.
PowerShell job errors, unexpected success-stream text, missing summaries, or a
failed worker make the aggregate fail.

## Payment gateway simulator

The simulator script always sends at least one `X-Simulate-*` header, selecting
the controller's local fault-injection branch and bypassing Stripe:

```powershell
./demo/traffic/Invoke-PaymentGatewaySimTraffic.ps1 `
  -Target Active `
  -BaseUrl http://127.0.0.1:18083 `
  -PortForwardServiceName eurotransit-payment-gateway-sim `
  -Mode Success

./demo/traffic/Invoke-PaymentGatewaySimTraffic.ps1 `
  -Target Active `
  -BaseUrl http://127.0.0.1:18083 `
  -PortForwardServiceName eurotransit-payment-gateway-sim `
  -Mode Latency `
  -LatencyMilliseconds 1500

./demo/traffic/Invoke-PaymentGatewaySimTraffic.ps1 `
  -Target Active `
  -BaseUrl http://127.0.0.1:18083 `
  -PortForwardServiceName eurotransit-payment-gateway-sim `
  -Mode Failure `
  -AllowFailureMode
```

Failure mode is opt-in and treats the expected HTTP 503 as a successful
fault-injection observation.

## Interruption and cleanup

CI exercises the shared `Stop-EuroTransitJobs` cleanup function and verifies
normal orchestrator completion leaves no jobs. It does not send a reliable
cross-platform Ctrl+C/SIGINT to a separate PowerShell process, so end-to-end
interrupt cleanup is not claimed.

Manual reproducible check:

```powershell
pwsh ./demo/traffic/Run-AllServicesTraffic.ps1 `
  -TrafficProfile ReadOnly `
  -DurationMinutes 20 `
  <required target and token parameters>

# Press Ctrl+C after workers have written traffic.
Get-Job -Name 'eurotransit-*'
Get-ChildItem demo/traffic/results -Recurse
```

Expected: the orchestrator terminates with an interruption exit status, no
`eurotransit-*` jobs remain, and workers that reached their `finally` block have
partial CSV/JSON evidence. Record the OS, PowerShell version, exit code, jobs,
and files when performing this manual check.

## Mock and optional live smoke

`tests/mock_eurotransit_server.py` is aligned with the checked-in controllers
and DTOs, and `SmokeTests.ps1` verifies those source contracts before using the
fixture. The mock provides fast deterministic coverage for status handling,
tokens, idempotency replay, profiles, and output shape.

A passing mock does not prove Kafka processing, real Inventory/Payments
integration, payment gateway configuration, service JWT audiences, or
end-to-end idempotency in the deployed system.

An optional read-only live check is available and is never executed
automatically by CI:

```powershell
./demo/traffic/Invoke-LiveContractSmoke.ps1 `
  -OrdersAccessToken $env:EUROTRANSIT_ORDERS_ACCESS_TOKEN `
  -DurationSeconds 30
```

## Local validation

```powershell
# Parse every PowerShell file
Get-ChildItem demo/traffic -Recurse -Filter '*.ps1' | ForEach-Object {
  $tokens = $null
  $errors = $null
  [System.Management.Automation.Language.Parser]::ParseFile(
    $_.FullName, [ref]$tokens, [ref]$errors
  ) | Out-Null
  if ($errors.Count) { throw $errors }
}

python -m py_compile demo/traffic/tests/mock_eurotransit_server.py
./demo/traffic/tests/SmokeTests.ps1
```

The smoke suite covers strict Public/DNS targets, missing/distinct tokens,
401/403/404/409/500, timeout classification, confirmed and expected business
money-path outcomes, duplicate identity, all gateway modes, ReadOnly,
MoneyPath, PerServiceBusiness, normal cleanup, and the cleanup function.
