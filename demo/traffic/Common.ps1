Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-EuroTransitAccessToken {
    param([string]$AccessToken)
    if ($AccessToken) { return $AccessToken }
    if ($env:EUROTRANSIT_ACCESS_TOKEN) { return $env:EUROTRANSIT_ACCESS_TOKEN }
    return $null
}

function Get-Percentile {
    param([double[]]$Values, [double]$Percentile)
    if (-not $Values -or $Values.Count -eq 0) { return 0 }
    $sorted = @($Values | Sort-Object)
    $index = [Math]::Ceiling(($Percentile / 100) * $sorted.Count) - 1
    return [Math]::Round($sorted[[Math]::Max(0, $index)], 2)
}

function Invoke-EuroTransitTraffic {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Service,
        [Parameter(Mandatory)][string]$Target,
        [Parameter(Mandatory)][uri]$Uri,
        [ValidateSet('GET', 'POST')][string]$Method = 'GET',
        [int]$DurationMinutes = 20,
        [int]$DurationSeconds = 0,
        [int]$RequestsPerMinute = 60,
        [int]$TimeoutSeconds = 10,
        [hashtable]$Headers = @{},
        [scriptblock]$BodyFactory,
        [string]$OutputDirectory = (Join-Path $PSScriptRoot 'results'),
        [double]$MaximumFailureRate = 0.01
    )

    if ($DurationMinutes -lt 1 -and $DurationSeconds -lt 1) { throw 'A positive duration is required.' }
    if ($RequestsPerMinute -lt 1) { throw 'RequestsPerMinute must be at least 1.' }
    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

    $started = Get-Date
    $deadline = if ($DurationSeconds -gt 0) { $started.AddSeconds($DurationSeconds) } else { $started.AddMinutes($DurationMinutes) }
    $records = [System.Collections.Generic.List[object]]::new()
    $baseDelayMs = 60000.0 / $RequestsPerMinute

    try {
        while ((Get-Date) -lt $deadline) {
            $requestStarted = Get-Date
            $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
            $statusCode = $null
            $outcome = 'success'
            $errorType = $null

            try {
                $parameters = @{
                    Uri = $Uri
                    Method = $Method
                    Headers = $Headers
                    TimeoutSec = $TimeoutSeconds
                    UseBasicParsing = $true
                }
                if ($BodyFactory) {
                    $parameters.ContentType = 'application/json'
                    $parameters.Body = (& $BodyFactory | ConvertTo-Json -Depth 8 -Compress)
                }
                $response = Invoke-WebRequest @parameters
                $statusCode = [int]$response.StatusCode
                if ($statusCode -ge 400) { $outcome = 'failure'; $errorType = 'http' }
            }
            catch {
                $outcome = 'failure'
                if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
                    $statusCode = [int]$_.Exception.Response.StatusCode
                    $errorType = 'http'
                }
                elseif ($_.Exception.Message -match 'timed out|timeout') {
                    $errorType = 'timeout'
                }
                else {
                    $errorType = 'network'
                }
            }
            finally {
                $stopwatch.Stop()
            }

            $records.Add([pscustomobject]@{
                service = $Service
                target = $Target
                timestamp = $requestStarted.ToUniversalTime().ToString('o')
                method = $Method
                uri = $Uri.AbsoluteUri
                status_code = $statusCode
                outcome = $outcome
                error_type = $errorType
                latency_ms = [Math]::Round($stopwatch.Elapsed.TotalMilliseconds, 2)
            })

            $jitter = Get-Random -Minimum 0.9 -Maximum 1.1
            $sleepMs = [Math]::Max(0, ($baseDelayMs * $jitter) - $stopwatch.Elapsed.TotalMilliseconds)
            if ($sleepMs -gt 0) { Start-Sleep -Milliseconds ([int]$sleepMs) }
        }
    }
    finally {
        $finished = Get-Date
        $latencies = @($records | ForEach-Object { [double]$_.latency_ms })
        $failures = @($records | Where-Object outcome -eq 'failure').Count
        $statusCounts = @{}
        foreach ($group in ($records | Where-Object status_code | Group-Object status_code)) {
            $statusCounts[$group.Name] = $group.Count
        }
        $summary = [pscustomobject]@{
            service = $Service
            target = $Target
            start = $started.ToUniversalTime().ToString('o')
            end = $finished.ToUniversalTime().ToString('o')
            total_requests = $records.Count
            successes = $records.Count - $failures
            failures = $failures
            failure_rate = if ($records.Count) { [Math]::Round($failures / $records.Count, 4) } else { 1 }
            status_codes = $statusCounts
            average_latency_ms = if ($latencies.Count) { [Math]::Round(($latencies | Measure-Object -Average).Average, 2) } else { 0 }
            p50_latency_ms = Get-Percentile $latencies 50
            p95_latency_ms = Get-Percentile $latencies 95
            maximum_latency_ms = if ($latencies.Count) { [Math]::Round(($latencies | Measure-Object -Maximum).Maximum, 2) } else { 0 }
            timeouts = @($records | Where-Object error_type -eq 'timeout').Count
            passed = ($records.Count -gt 0 -and ($failures / $records.Count) -le $MaximumFailureRate)
        }

        $stamp = $started.ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
        $prefix = Join-Path $OutputDirectory "$Service-$Target-$stamp"
        $records | Export-Csv -NoTypeInformation -Encoding UTF8 -Path "$prefix.csv"
        $summary | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -Path "$prefix-summary.json"
        $summary
    }
}
