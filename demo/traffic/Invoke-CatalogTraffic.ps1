[CmdletBinding()]
param(
    [ValidateSet('Public', 'Stable', 'Canary', 'Active', 'Preview')]
    [string]$Target = 'Public',
    [string]$BaseUrl,
    [string]$PortForwardServiceName,
    [string]$ExpectedPublicHost = 'g02.cpo2026.it',
    [ValidateSet('http', 'https')][string]$ExpectedPublicScheme = 'https',
    [ValidateRange(1, 65535)][int]$ExpectedPublicPort = 443,
    [string]$KubernetesNamespace = 'eurotransit',
    [int]$DurationMinutes = 20,
    [int]$DurationSeconds = 0,
    [ValidateRange(1, 6000)][int]$RequestsPerMinute = 60,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'results')
)

. (Join-Path $PSScriptRoot 'Common.ps1')

$destination = Resolve-EuroTransitDestination -Service catalog -Target $Target `
    -BaseUrl $BaseUrl -PortForwardServiceName $PortForwardServiceName `
    -ExpectedPublicHost $ExpectedPublicHost -ExpectedPublicScheme $ExpectedPublicScheme `
    -ExpectedPublicPort $ExpectedPublicPort -KubernetesNamespace $KubernetesNamespace
Invoke-EuroTransitTraffic -Service catalog -Destination $destination `
    -Path '/api/v1/catalog/products' -DurationMinutes $DurationMinutes `
    -DurationSeconds $DurationSeconds -RequestsPerMinute $RequestsPerMinute `
    -TrafficMode 'application-read' -OutputDirectory $OutputDirectory
