param(
    [ValidateSet('Active','Preview')][string]$Target = 'Active',
    [uri]$BaseUrl = 'http://127.0.0.1:18082',
    [string]$AccessToken,
    [switch]$EnableWrites,
    [int]$DurationMinutes = 20,
    [int]$RequestsPerMinute = 60,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'results')
)
. (Join-Path $PSScriptRoot 'Common.ps1')
if (-not $EnableWrites) {
    Invoke-EuroTransitTraffic -Service payments -Target $Target -Uri ([uri]::new($BaseUrl, '/actuator/health/readiness')) -DurationMinutes $DurationMinutes -RequestsPerMinute $RequestsPerMinute -OutputDirectory $OutputDirectory
    return
}
if ($RequestsPerMinute -gt 10) { throw 'Payments write traffic is capped at 10 requests per minute.' }
$token = Get-EuroTransitAccessToken $AccessToken
if (-not $token) { throw 'Payments write traffic requires -AccessToken or EUROTRANSIT_ACCESS_TOKEN.' }
$key = "demo-payment-$([guid]::NewGuid())"
$body = { @{ idempotency_key=$key; user_id='demo-user'; amount=1.00; currency='EUR' } }.GetNewClosure()
Invoke-EuroTransitTraffic -Service payments -Target $Target -Uri ([uri]::new($BaseUrl, '/api/v1/payments/authorize')) -Method POST -Headers @{Authorization="Bearer $token"} -BodyFactory $body -DurationMinutes $DurationMinutes -RequestsPerMinute $RequestsPerMinute -OutputDirectory $OutputDirectory
