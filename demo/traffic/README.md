# EuroTransit demo traffic

These PowerShell scripts generate observable traffic only. They never promote,
abort, retry, or otherwise modify an Argo Rollout. Notifications is deliberately
excluded.

PowerShell 7 is recommended. Results are written as request-level CSV files and
JSON summaries under `demo/traffic/results/`.

```powershell
$env:EUROTRANSIT_ACCESS_TOKEN = '<short-lived token>'
./demo/traffic/Run-AllServicesTraffic.ps1 `
  -DurationMinutes 20 `
  -RequestsPerMinute 60
```

Orders requires `-AccessToken` or `EUROTRANSIT_ACCESS_TOKEN`; the token is never
printed or persisted. Inventory and Payments use their unauthenticated readiness
endpoint by default. Their real POST endpoints require both `-EnableWrites` and a
token, are capped at 10 requests/minute, and reuse one idempotency key for the
whole run.

Internal services require port-forwards before running the orchestrator:

```powershell
kubectl port-forward -n eurotransit svc/eurotransit-inventory 18081:8080
kubectl port-forward -n eurotransit svc/eurotransit-payments 18082:8080
kubectl port-forward -n eurotransit svc/eurotransit-payment-gateway-sim 18083:8080
```

For Blue/Green preview, forward the `-preview` Service and pass `-Target Preview`.
Automated backend Blue/Green analysis also requires valid non-actuator traffic;
run the Inventory/Payments scripts with `-EnableWrites`, a short-lived token,
and at most 10 requests/minute against the preview Service before promotion.
For Canary stable/candidate, forward the stable or `-canary` Service and pass the
matching `-BaseUrl`. `Target Public` always traverses Traefik and therefore
respects the real traffic weights.

Press `Ctrl+C` to stop. Each worker writes its available records and summary from
its `finally` block; the orchestrator also stops and removes all PowerShell jobs.
