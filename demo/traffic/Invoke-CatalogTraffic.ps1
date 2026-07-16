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
    [ValidateSet('Smoke', 'Rollout')][string]$Profile = 'Smoke',
    [int]$DurationMinutes = 20,
    [int]$DurationSeconds = 0,
    [ValidateRange(1, 6000)][int]$RequestsPerMinute = 60,
    [ValidateRange(1, 300)][int]$TimeoutSeconds,
    [ValidateRange(1, 50)][int]$MaxConcurrency,
    [ValidateRange(1, 100)][int]$MinimumRequestVolumePercentage,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'results')
)

$boundParameters = @{} + $PSBoundParameters
. (Join-Path $PSScriptRoot 'Common.ps1')
$settings = Resolve-EuroTransitTrafficSettings -Profile $Profile -BoundParameters $boundParameters `
    -DurationMinutes $DurationMinutes -DurationSeconds $DurationSeconds `
    -RequestsPerMinute $RequestsPerMinute -TimeoutSeconds $TimeoutSeconds `
    -MaxConcurrency $MaxConcurrency -MinimumRequestVolumePercentage $MinimumRequestVolumePercentage

$destination = Resolve-EuroTransitDestination -Service catalog -Target $Target `
    -BaseUrl $BaseUrl -PortForwardServiceName $PortForwardServiceName `
    -ExpectedPublicHost $ExpectedPublicHost -ExpectedPublicScheme $ExpectedPublicScheme `
    -ExpectedPublicPort $ExpectedPublicPort -KubernetesNamespace $KubernetesNamespace
Invoke-EuroTransitTraffic -Service catalog -Destination $destination `
    -Path '/api/v1/catalog/products' -DurationMinutes $settings.DurationMinutes `
    -DurationSeconds $settings.DurationSeconds -RequestsPerMinute $settings.RequestsPerMinute `
    -TimeoutSeconds $settings.TimeoutSeconds -MaxConcurrency $settings.MaxConcurrency `
    -MinimumRequestVolumePercentage $settings.MinimumRequestVolumePercentage `
    -TrafficMode 'application-read' -OutputDirectory $OutputDirectory
