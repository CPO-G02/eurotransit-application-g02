param(
    [ValidateSet('Public','Stable','Canary','Active','Preview')][string]$Target = 'Public',
    [uri]$BaseUrl = 'https://g02.cpo2026.it',
    [int]$DurationMinutes = 20,
    [int]$RequestsPerMinute = 60,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'results')
)
. (Join-Path $PSScriptRoot 'Common.ps1')
Invoke-EuroTransitTraffic -Service catalog -Target $Target -Uri ([uri]::new($BaseUrl, '/api/v1/catalog/products')) -DurationMinutes $DurationMinutes -RequestsPerMinute $RequestsPerMinute -OutputDirectory $OutputDirectory
