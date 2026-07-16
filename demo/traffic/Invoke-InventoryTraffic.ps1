param(
    [ValidateSet('Active','Preview')][string]$Target = 'Active',
    [uri]$BaseUrl = 'http://127.0.0.1:18081',
    [string]$AccessToken,
    [switch]$EnableWrites,
    [int]$DurationMinutes = 20,
    [int]$RequestsPerMinute = 60,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'results')
)
. (Join-Path $PSScriptRoot 'Common.ps1')
if (-not $EnableWrites) {
    Invoke-EuroTransitTraffic -Service inventory -Target $Target -Uri ([uri]::new($BaseUrl, '/actuator/health/readiness')) -DurationMinutes $DurationMinutes -RequestsPerMinute $RequestsPerMinute -OutputDirectory $OutputDirectory
    return
}
if ($RequestsPerMinute -gt 10) { throw 'Inventory write traffic is capped at 10 requests per minute.' }
$token = Get-EuroTransitAccessToken $AccessToken
if (-not $token) { throw 'Inventory write traffic requires -AccessToken or EUROTRANSIT_ACCESS_TOKEN.' }
$key = "demo-inventory-$([guid]::NewGuid())"
$body = { @{ idempotency_key=$key; train_id='TR-101'; seat_class='economy'; quantity=1 } }.GetNewClosure()
Invoke-EuroTransitTraffic -Service inventory -Target $Target -Uri ([uri]::new($BaseUrl, '/reserve')) -Method POST -Headers @{Authorization="Bearer $token"} -BodyFactory $body -DurationMinutes $DurationMinutes -RequestsPerMinute $RequestsPerMinute -OutputDirectory $OutputDirectory
