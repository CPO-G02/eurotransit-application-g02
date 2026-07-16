[CmdletBinding()]
param(
    [int]$DurationMinutes = 20,
    [int]$DurationSeconds = 0,
    [string]$AccessToken,
    [switch]$EnableBusinessTraffic,
    [switch]$AcknowledgeBusinessSideEffects,

    [ValidateSet('Public', 'Stable', 'Canary', 'Active', 'Preview')][string]$FrontendTarget = 'Public',
    [string]$FrontendBaseUrl,
    [string]$FrontendPortForwardServiceName,
    [ValidateRange(1, 6000)][int]$FrontendRequestsPerMinute = 60,

    [ValidateSet('Public', 'Stable', 'Canary', 'Active', 'Preview')][string]$CatalogTarget = 'Public',
    [string]$CatalogBaseUrl,
    [string]$CatalogPortForwardServiceName,
    [ValidateRange(1, 6000)][int]$CatalogRequestsPerMinute = 60,

    [ValidateSet('Public', 'Stable', 'Canary', 'Active', 'Preview')][string]$OrdersTarget = 'Public',
    [string]$OrdersBaseUrl,
    [string]$OrdersPortForwardServiceName,
    [ValidateRange(1, 6000)][int]$OrdersRequestsPerMinute = 60,

    [ValidateSet('Active', 'Preview')][string]$InventoryTarget = 'Active',
    [string]$InventoryBaseUrl,
    [string]$InventoryPortForwardServiceName,
    [ValidateRange(1, 6000)][int]$InventoryRequestsPerMinute = 60,

    [ValidateSet('Active', 'Preview')][string]$PaymentsTarget = 'Active',
    [string]$PaymentsBaseUrl,
    [string]$PaymentsPortForwardServiceName,
    [ValidateRange(1, 6000)][int]$PaymentsRequestsPerMinute = 60,

    [ValidateSet('Active', 'Preview')][string]$GatewayTarget = 'Active',
    [string]$GatewayBaseUrl,
    [string]$GatewayPortForwardServiceName,
    [ValidateSet('Success', 'Latency')][string]$GatewayMode = 'Success',
    [ValidateRange(1, 10)][int]$GatewayRequestsPerMinute = 10,

    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'results')
)

if ($EnableBusinessTraffic -and -not $AcknowledgeBusinessSideEffects) {
    throw 'Business mode creates orders, reserves seats, and invokes the configured payment gateway. Pass -AcknowledgeBusinessSideEffects only in a controlled environment.'
}

$ordersMode = if ($EnableBusinessTraffic) { 'MoneyPath' } else { 'ReadOnly' }
$internalMode = if ($EnableBusinessTraffic) { 'Business' } else { 'Readiness' }
$scripts = @(
    @{
        Name = 'frontend'
        File = 'Invoke-FrontendTraffic.ps1'
        Args = @{
            Target = $FrontendTarget
            BaseUrl = $FrontendBaseUrl
            PortForwardServiceName = $FrontendPortForwardServiceName
            RequestsPerMinute = $FrontendRequestsPerMinute
        }
    },
    @{
        Name = 'catalog'
        File = 'Invoke-CatalogTraffic.ps1'
        Args = @{
            Target = $CatalogTarget
            BaseUrl = $CatalogBaseUrl
            PortForwardServiceName = $CatalogPortForwardServiceName
            RequestsPerMinute = $CatalogRequestsPerMinute
        }
    },
    @{
        Name = 'orders'
        File = 'Invoke-OrdersTraffic.ps1'
        Args = @{
            Target = $OrdersTarget
            BaseUrl = $OrdersBaseUrl
            PortForwardServiceName = $OrdersPortForwardServiceName
            Mode = $ordersMode
            AccessToken = $AccessToken
            AcknowledgeMoneyPathSideEffects = [bool]$EnableBusinessTraffic
            CatalogTarget = $CatalogTarget
            CatalogBaseUrl = $CatalogBaseUrl
            CatalogPortForwardServiceName = $CatalogPortForwardServiceName
            RequestsPerMinute = if ($EnableBusinessTraffic) { [Math]::Min(10, $OrdersRequestsPerMinute) } else { $OrdersRequestsPerMinute }
        }
    },
    @{
        Name = 'inventory'
        File = 'Invoke-InventoryTraffic.ps1'
        Args = @{
            Target = $InventoryTarget
            BaseUrl = $InventoryBaseUrl
            PortForwardServiceName = $InventoryPortForwardServiceName
            Mode = $internalMode
            AccessToken = $AccessToken
            AcknowledgeSeatConsumption = [bool]$EnableBusinessTraffic
            CatalogTarget = $CatalogTarget
            CatalogBaseUrl = $CatalogBaseUrl
            CatalogPortForwardServiceName = $CatalogPortForwardServiceName
            RequestsPerMinute = if ($EnableBusinessTraffic) { [Math]::Min(10, $InventoryRequestsPerMinute) } else { $InventoryRequestsPerMinute }
        }
    },
    @{
        Name = 'payments'
        File = 'Invoke-PaymentsTraffic.ps1'
        Args = @{
            Target = $PaymentsTarget
            BaseUrl = $PaymentsBaseUrl
            PortForwardServiceName = $PaymentsPortForwardServiceName
            Mode = $internalMode
            AccessToken = $AccessToken
            AcknowledgeSafeGatewayConfiguration = [bool]$EnableBusinessTraffic
            RequestsPerMinute = if ($EnableBusinessTraffic) { [Math]::Min(10, $PaymentsRequestsPerMinute) } else { $PaymentsRequestsPerMinute }
        }
    },
    @{
        Name = 'payment-gateway-sim'
        File = 'Invoke-PaymentGatewaySimTraffic.ps1'
        Args = @{
            Target = $GatewayTarget
            BaseUrl = $GatewayBaseUrl
            PortForwardServiceName = $GatewayPortForwardServiceName
            Mode = $GatewayMode
            RequestsPerMinute = $GatewayRequestsPerMinute
        }
    }
)

$jobs = [System.Collections.Generic.List[object]]::new()
$summaries = @()
$jobErrors = @()
$unexpectedOutput = @()
$workerFailed = $false
$aggregate = $null

try {
    foreach ($item in $scripts) {
        $parameters = @{
            DurationMinutes = $DurationMinutes
            DurationSeconds = $DurationSeconds
            OutputDirectory = $OutputDirectory
        }
        foreach ($entry in $item.Args.GetEnumerator()) {
            if ($null -ne $entry.Value -and $entry.Value -ne '') {
                $parameters[$entry.Key] = $entry.Value
            }
        }
        $job = Start-Job -Name "eurotransit-$($item.Name)-$([guid]::NewGuid())" -ScriptBlock {
            param($ScriptPath, $Parameters)
            & $ScriptPath @Parameters
        } -ArgumentList (Join-Path $PSScriptRoot $item.File), $parameters
        $jobs.Add($job)
    }

    Wait-Job -Job $jobs | Out-Null
    $workerFailed = @($jobs | Where-Object State -ne 'Completed').Count -gt 0
    $workerOutput = @($jobs | Receive-Job -ErrorAction Continue -ErrorVariable +jobErrors)
    $summaries = @($workerOutput | Where-Object {
        $_.PSObject.TypeNames -contains 'EuroTransit.TrafficSummary' -or
        $_.PSObject.TypeNames -contains 'Deserialized.EuroTransit.TrafficSummary'
    })
    $unexpectedOutput = @($workerOutput | Where-Object {
        $_.PSObject.TypeNames -notcontains 'EuroTransit.TrafficSummary' -and
        $_.PSObject.TypeNames -notcontains 'Deserialized.EuroTransit.TrafficSummary'
    })

    $summaries |
        Select-Object service, target, destination_service, traffic_mode, application_traffic, total_requests, failures, p95_latency_ms, passed |
        Format-Table -AutoSize |
        Out-Host

    $passed = -not $workerFailed -and $jobErrors.Count -eq 0 `
        -and $unexpectedOutput.Count -eq 0 -and $summaries.Count -eq $scripts.Count `
        -and @($summaries | Where-Object { -not $_.passed }).Count -eq 0
    $aggregate = [pscustomobject]@{
        service = 'all-services'
        target = 'mixed'
        traffic_mode = if ($EnableBusinessTraffic) { 'explicit-business' } else { 'safe-readiness-and-read-only' }
        application_traffic = @($summaries | Where-Object application_traffic).Count -gt 0
        business_traffic = [bool]$EnableBusinessTraffic
        worker_count = $scripts.Count
        summary_count = $summaries.Count
        worker_errors = $jobErrors.Count
        unexpected_success_output = $unexpectedOutput.Count
        passed = $passed
        services = $summaries
    }
    $aggregate.PSObject.TypeNames.Insert(0, 'EuroTransit.OrchestratorSummary')
    $aggregate

    if (-not $passed) {
        throw "One or more traffic workers failed. summaries=$($summaries.Count)/$($scripts.Count), errors=$($jobErrors.Count), unexpectedOutput=$($unexpectedOutput.Count)."
    }
}
finally {
    if ($jobs.Count -gt 0) {
        $jobs | Stop-Job -ErrorAction SilentlyContinue
        $jobs | Remove-Job -Force -ErrorAction SilentlyContinue
    }
}
