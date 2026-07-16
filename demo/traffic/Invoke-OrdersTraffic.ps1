param(
    [ValidateSet('Public','Stable','Canary','Active','Preview')][string]$Target = 'Public',
    [uri]$BaseUrl = 'https://g02.cpo2026.it',
    [string]$AccessToken,
    [int]$DurationMinutes = 20,
    [int]$RequestsPerMinute = 60,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'results')
)
. (Join-Path $PSScriptRoot 'Common.ps1')
$token = Get-EuroTransitAccessToken $AccessToken
if (-not $token) { throw 'Orders traffic requires -AccessToken or EUROTRANSIT_ACCESS_TOKEN.' }
$headers = @{ Authorization = "Bearer $token" }
Invoke-EuroTransitTraffic -Service orders -Target $Target -Uri ([uri]::new($BaseUrl, '/api/v1/orders')) -Headers $headers -DurationMinutes $DurationMinutes -RequestsPerMinute $RequestsPerMinute -OutputDirectory $OutputDirectory
