[CmdletBinding()]
param(
    [string]$OrdersAccessToken,
    [string]$PublicBaseUrl = 'https://g02.cpo2026.it',
    [string]$ExpectedPublicHost = 'g02.cpo2026.it',
    [ValidateSet('http', 'https')][string]$ExpectedPublicScheme = 'https',
    [ValidateRange(1, 65535)][int]$ExpectedPublicPort = 443,
    [ValidateRange(1, 300)][int]$DurationSeconds = 30,
    [ValidateRange(1, 600)][int]$RequestsPerMinute = 30,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'results/live-smoke')
)

# Optional operator-invoked smoke test. CI only parses this file; it never calls
# the public environment. This deliberately remains read-only.
$summaries = @(
    & (Join-Path $PSScriptRoot 'Invoke-FrontendTraffic.ps1') `
        -Target Public -BaseUrl $PublicBaseUrl `
        -ExpectedPublicHost $ExpectedPublicHost -ExpectedPublicScheme $ExpectedPublicScheme `
        -ExpectedPublicPort $ExpectedPublicPort -DurationSeconds $DurationSeconds `
        -RequestsPerMinute $RequestsPerMinute -OutputDirectory $OutputDirectory
    & (Join-Path $PSScriptRoot 'Invoke-CatalogTraffic.ps1') `
        -Target Public -BaseUrl $PublicBaseUrl `
        -ExpectedPublicHost $ExpectedPublicHost -ExpectedPublicScheme $ExpectedPublicScheme `
        -ExpectedPublicPort $ExpectedPublicPort -DurationSeconds $DurationSeconds `
        -RequestsPerMinute $RequestsPerMinute -OutputDirectory $OutputDirectory
    & (Join-Path $PSScriptRoot 'Invoke-OrdersTraffic.ps1') `
        -Target Public -BaseUrl $PublicBaseUrl `
        -ExpectedPublicHost $ExpectedPublicHost -ExpectedPublicScheme $ExpectedPublicScheme `
        -ExpectedPublicPort $ExpectedPublicPort -AccessToken $OrdersAccessToken `
        -Mode ReadOnly -DurationSeconds $DurationSeconds `
        -RequestsPerMinute $RequestsPerMinute -OutputDirectory $OutputDirectory
)

$passed = $summaries.Count -eq 3 -and @($summaries | Where-Object { -not $_.passed }).Count -eq 0
$result = [pscustomobject]@{
    service = 'live-contract-smoke'
    target = 'Public'
    traffic_mode = 'read-only'
    summary_count = $summaries.Count
    passed = $passed
    services = $summaries
}
$result.PSObject.TypeNames.Insert(0, 'EuroTransit.LiveSmokeSummary')
$result

if (-not $passed) {
    throw 'One or more optional live read-only smoke checks failed.'
}
