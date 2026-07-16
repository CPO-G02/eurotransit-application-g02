# EuroTransit demo traffic

These PowerShell 7 scripts generate controlled HTTP traffic and evidence files.
They never promote, abort, retry, patch, or synchronize a Rollout and they never
start a port-forward. The operator owns any `kubectl port-forward` process.
Notifications is deliberately outside the demo scope.

Every script emits one structured `EuroTransit.TrafficSummary` object and writes
request-level CSV plus a JSON summary under `demo/traffic/results/`. Access
tokens are read from `-AccessToken` or `EUROTRANSIT_ACCESS_TOKEN` and are never
written to those files.

## Destination contract

`Target` is not a label-only field. It determines the expected destination:

| Target | Expected service |
|---|---|
| `Public` | `https://g02.cpo2026.it` through Traefik |
| `Stable` / `Active` | `eurotransit-<service>` |
| `Canary` | `eurotransit-<service>-canary` |
| `Preview` | `eurotransit-<service>-preview` |

For every non-public target, `-BaseUrl` is mandatory. If it is loopback, the
matching `-PortForwardServiceName` is also mandatory; the script rejects a
Canary target asserted against the stable Service, a Preview target asserted
against active, or any cross-service mismatch. For in-cluster DNS, the hostname
itself must begin with the expected Service name.

Example Catalog Canary:

```powershell
kubectl port-forward -n eurotransit svc/eurotransit-catalog-canary 18080:8080

./demo/traffic/Invoke-CatalogTraffic.ps1 `
  -Target Canary `
  -BaseUrl http://127.0.0.1:18080 `
  -PortForwardServiceName eurotransit-catalog-canary `
  -DurationMinutes 5 `
  -RequestsPerMinute 60
```

`Target Public` is the only mode that relies on Traefik's real Canary weights.
A direct `*-canary` or `*-preview` Service intentionally sends all generated
traffic to that candidate.

## Read-only and business modes

Frontend and Catalog always send application GET traffic.

Orders defaults to authenticated read-only listing. `MoneyPath` is explicit and
state-changing: it selects a currently offered train/seat class from Catalog,
creates an order with a fresh idempotency key, optionally replays the exact same
key and payload, polls the order to `CONFIRMED` or `FAILED`, and reports
end-to-end latency, terminal outcomes, timeouts, and inconsistent duplicate
identities.

```powershell
$env:EUROTRANSIT_ACCESS_TOKEN = '<short-lived token>'

./demo/traffic/Invoke-OrdersTraffic.ps1 `
  -Target Public `
  -CatalogTarget Public `
  -Mode MoneyPath `
  -AcknowledgeMoneyPathSideEffects `
  -MaxNewOperations 3 `
  -DuplicatePercentage 25 `
  -RequestsPerMinute 5 `
  -DurationMinutes 5
```

This flow reserves real seats and invokes the Payments configuration used by
Orders. Keep the operation cap low and verify that the environment points to a
safe/test payment gateway first. Orders automation remains disabled in the
configuration chart because current service metrics cannot attribute completion
of the asynchronous Inventory/Payments/Kafka money path to the candidate HTTP
revision; this script supplies demonstration evidence, not a false automatic
promotion signal.

Inventory and Payments default to readiness-only traffic. Their summaries set
`application_traffic=false`, so they cannot be mistaken for a valid Blue/Green
preview load. Business mode is required for automated preview analysis:

```powershell
kubectl port-forward -n eurotransit svc/eurotransit-inventory-preview 18081:8080

./demo/traffic/Invoke-InventoryTraffic.ps1 `
  -Target Preview `
  -BaseUrl http://127.0.0.1:18081 `
  -PortForwardServiceName eurotransit-inventory-preview `
  -Mode Business `
  -AcknowledgeSeatConsumption `
  -CatalogTarget Public `
  -RequestsPerMinute 10 `
  -MaxNewOperations 2 `
  -DuplicatePercentage 90 `
  -DurationMinutes 5
```

Inventory no longer hardcodes a train or class. It either selects them from
Catalog or requires both `-TrainId` and `-SeatClass`. Catalog availability is a
nominal snapshot rather than Inventory's authoritative count, so HTTP 409 is
reported separately as `insufficient_seats`. Each new reservation gets a fresh
key; duplicate traffic reuses the exact original key and payload and verifies
the same `reservation_id`.

Payments Business mode is similarly capped at 10 requests/minute. It requires
`-AcknowledgeSafeGatewayConfiguration` because the Payments service invokes
whatever gateway is configured in that environment. Successful duplicate
authorizations must return the same `transaction_id`.

## Payment gateway simulator

The simulator script always sends `X-Simulate-*` headers, so it takes the local
test branch and cannot accidentally fall through to Stripe:

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

Every request uses a unique `order_id`. Failure mode is opt-in and considers the
expected HTTP 503 a successful fault-injection observation.

## Parallel orchestrator

The orchestrator has independent target, base URL, port-forward assertion, and
rate parameters for every service. This allows Catalog Canary, Inventory
Preview, and Payments Active to be exercised in the same run without pretending
they share one destination.

```powershell
./demo/traffic/Run-AllServicesTraffic.ps1 `
  -DurationMinutes 5 `
  -AccessToken $env:EUROTRANSIT_ACCESS_TOKEN `
  -FrontendTarget Public `
  -CatalogTarget Canary `
  -CatalogBaseUrl http://127.0.0.1:18080 `
  -CatalogPortForwardServiceName eurotransit-catalog-canary `
  -OrdersTarget Public `
  -InventoryTarget Preview `
  -InventoryBaseUrl http://127.0.0.1:18081 `
  -InventoryPortForwardServiceName eurotransit-inventory-preview `
  -PaymentsTarget Preview `
  -PaymentsBaseUrl http://127.0.0.1:18082 `
  -PaymentsPortForwardServiceName eurotransit-payments-preview `
  -GatewayTarget Active `
  -GatewayBaseUrl http://127.0.0.1:18083 `
  -GatewayPortForwardServiceName eurotransit-payment-gateway-sim
```

That default run keeps Orders read-only and Inventory/Payments readiness-only.
To create business traffic, add both `-EnableBusinessTraffic` and
`-AcknowledgeBusinessSideEffects`; the orchestrator then enables the Orders
money path and low-rate idempotent Inventory/Payments traffic.

Worker success output is accepted only when it is a structured summary.
PowerShell job errors, unexpected success-stream text, missing summaries, or any
failed service make the orchestrator fail. `Ctrl+C` enters the `finally` cleanup,
stopping and removing every job started by the orchestrator. Individual workers
also write their partial record set and summary from `finally`.

## Local validation

The smoke suite starts a local HTTP fixture and covers 2xx, 401, 403, 404, 409,
5xx, timeout classification, target mismatch rejection, Orders terminal polling,
idempotent duplicate identity, all gateway modes, structured output, and job
cleanup:

```powershell
./demo/traffic/tests/SmokeTests.ps1
```
