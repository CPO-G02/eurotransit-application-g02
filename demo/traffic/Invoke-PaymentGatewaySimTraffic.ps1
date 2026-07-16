param(
    [ValidateSet('Active','Preview')][string]$Target = 'Active',
    [uri]$BaseUrl = 'http://127.0.0.1:18083',
    [int]$DurationMinutes = 20,
    [ValidateRange(1,10)][int]$RequestsPerMinute = 10,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'results')
)
. (Join-Path $PSScriptRoot 'Common.ps1')
$body = { @{ order_id="demo-gateway-$([guid]::NewGuid())"; amount=1.00; currency='EUR' } }
$headers = @{ 'X-Simulate-Delay-Ms' = '0' }
Invoke-EuroTransitTraffic -Service payment-gateway-sim -Target $Target -Uri ([uri]::new($BaseUrl, '/gateway/charge')) -Method POST -Headers $headers -BodyFactory $body -DurationMinutes $DurationMinutes -RequestsPerMinute $RequestsPerMinute -OutputDirectory $OutputDirectory
