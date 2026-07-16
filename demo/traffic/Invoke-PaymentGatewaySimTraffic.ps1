[CmdletBinding()]
param(
    [ValidateSet('Active', 'Preview')][string]$Target = 'Active',
    [string]$BaseUrl,
    [string]$PortForwardServiceName,
    [string]$KubernetesNamespace = 'eurotransit',
    [ValidateSet('Success', 'Latency', 'Failure')][string]$Mode = 'Success',
    [ValidateRange(1, 30000)][int]$LatencyMilliseconds = 1500,
    [switch]$AllowFailureMode,
    [int]$DurationMinutes = 20,
    [int]$DurationSeconds = 0,
    [ValidateRange(1, 10)][int]$RequestsPerMinute = 10,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'results')
)

. (Join-Path $PSScriptRoot 'Common.ps1')

if ($Mode -eq 'Failure' -and -not $AllowFailureMode) {
    throw 'Gateway Failure mode intentionally returns HTTP 503. Pass -AllowFailureMode to opt in.'
}

$destination = Resolve-EuroTransitDestination -Service payment-gateway-sim -Target $Target `
    -BaseUrl $BaseUrl -PortForwardServiceName $PortForwardServiceName `
    -KubernetesNamespace $KubernetesNamespace
$headers = switch ($Mode) {
    'Success' { @{ 'X-Simulate-Delay-Ms' = '0' } }
    'Latency' { @{ 'X-Simulate-Delay-Ms' = [string]$LatencyMilliseconds } }
    'Failure' { @{ 'X-Simulate-Delay-Ms' = '0'; 'X-Simulate-Failure' = 'true' } }
}
$expectedStatusCodes = if ($Mode -eq 'Failure') { @(503) } else { @(200) }
$bodyFactory = {
    @{
        order_id = "demo-gateway-$([guid]::NewGuid())"
        amount = 1.00
        currency = 'EUR'
    }
}.GetNewClosure()

Invoke-EuroTransitTraffic -Service payment-gateway-sim -Destination $destination `
    -Path '/gateway/charge' -Method POST -Headers $headers -BodyFactory $bodyFactory `
    -ExpectedStatusCodes $expectedStatusCodes -TrafficMode "simulation-$($Mode.ToLowerInvariant())" `
    -DurationMinutes $DurationMinutes -DurationSeconds $DurationSeconds `
    -RequestsPerMinute $RequestsPerMinute -OutputDirectory $OutputDirectory `
    -MaximumFailureRate 0
