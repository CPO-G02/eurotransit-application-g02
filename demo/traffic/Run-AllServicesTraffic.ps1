param(
    [int]$DurationMinutes = 20,
    [int]$RequestsPerMinute = 60,
    [string]$AccessToken,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'results')
)

$scripts = @(
    @{ Name='frontend'; File='Invoke-FrontendTraffic.ps1'; Args=@{} },
    @{ Name='catalog'; File='Invoke-CatalogTraffic.ps1'; Args=@{} },
    @{ Name='orders'; File='Invoke-OrdersTraffic.ps1'; Args=@{AccessToken=$AccessToken} },
    @{ Name='inventory'; File='Invoke-InventoryTraffic.ps1'; Args=@{BaseUrl='http://127.0.0.1:18081'} },
    @{ Name='payments'; File='Invoke-PaymentsTraffic.ps1'; Args=@{BaseUrl='http://127.0.0.1:18082'} },
    @{ Name='payment-gateway-sim'; File='Invoke-PaymentGatewaySimTraffic.ps1'; Args=@{BaseUrl='http://127.0.0.1:18083'; RequestsPerMinute=[Math]::Min(10,$RequestsPerMinute)} }
)
$jobs = @()
try {
    foreach ($item in $scripts) {
        $parameters = @{
            DurationMinutes = $DurationMinutes
            RequestsPerMinute = $RequestsPerMinute
            OutputDirectory = $OutputDirectory
        }
        foreach ($entry in $item.Args.GetEnumerator()) {
            if ($null -ne $entry.Value -and $entry.Value -ne '') { $parameters[$entry.Key] = $entry.Value }
        }
        $jobs += Start-Job -Name $item.Name -ScriptBlock {
            param($ScriptPath, $Parameters)
            & $ScriptPath @Parameters
        } -ArgumentList (Join-Path $PSScriptRoot $item.File), $parameters
    }

    Wait-Job -Job $jobs | Out-Null
    $summaries = @($jobs | Receive-Job)
    $summaries | Select-Object service,target,total_requests,successes,failures,failure_rate,p95_latency_ms,timeouts,passed | Format-Table -AutoSize
    $workerFailed = @($jobs | Where-Object State -ne 'Completed').Count -gt 0
    $summaryMissing = $summaries.Count -ne $scripts.Count
    if ($workerFailed -or $summaryMissing -or @($summaries | Where-Object { -not $_.passed }).Count -gt 0) { exit 1 }
}
finally {
    $jobs | Stop-Job -ErrorAction SilentlyContinue
    $jobs | Remove-Job -Force -ErrorAction SilentlyContinue
}
