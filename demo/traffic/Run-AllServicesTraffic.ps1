[CmdletBinding()]
param(
    [int]$DurationMinutes = 20,
    [int]$DurationSeconds = 0,
    [string]$AccessToken,
    [string]$OrdersAccessToken,
    [string]$InventoryAccessToken,
    [string]$PaymentsAccessToken,
    [ValidateSet('ReadOnly', 'MoneyPath', 'PerServiceBusiness', 'CombinedExplicit')]
    [string]$TrafficProfile = 'ReadOnly',
    [switch]$AcknowledgeMoneyPathSideEffects,
    [switch]$AcknowledgePerServiceBusinessSideEffects,
    [switch]$AcknowledgeCombinedSideEffects,
    [switch]$AcknowledgeSafePaymentGateway,
    [switch]$IncludeGatewaySimulator,
    [string]$ExpectedPublicHost = 'g02.cpo2026.it',
    [ValidateSet('http', 'https')][string]$ExpectedPublicScheme = 'https',
    [ValidateRange(1, 65535)][int]$ExpectedPublicPort = 443,
    [string]$KubernetesNamespace = 'eurotransit',

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
    [string]$OrdersTrainId,
    [string]$OrdersSeatClass,
    [ValidateSet('Confirmed', 'InsufficientInventory', 'PaymentDeclined')]
    [string]$OrdersExpectedOutcome = 'Confirmed',
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

. (Join-Path $PSScriptRoot 'Common.ps1')

$moneyPathEnabled = $TrafficProfile -in @('MoneyPath', 'CombinedExplicit')
$perServiceBusinessEnabled = $TrafficProfile -in @('PerServiceBusiness', 'CombinedExplicit')

if ($moneyPathEnabled -and -not $AcknowledgeMoneyPathSideEffects) {
    throw 'MoneyPath creates real orders and consumes Inventory through Orders. Pass -AcknowledgeMoneyPathSideEffects only in a controlled environment.'
}
if ($perServiceBusinessEnabled -and -not $AcknowledgePerServiceBusinessSideEffects) {
    throw 'PerServiceBusiness directly reserves seats and authorizes payments. Pass -AcknowledgePerServiceBusinessSideEffects only in a controlled environment.'
}
if ($TrafficProfile -eq 'CombinedExplicit' -and -not $AcknowledgeCombinedSideEffects) {
    throw 'CombinedExplicit duplicates downstream effects in addition to the Orders money path. Pass -AcknowledgeCombinedSideEffects to confirm this specific combined test.'
}
if (($moneyPathEnabled -or $perServiceBusinessEnabled) -and -not $AcknowledgeSafePaymentGateway) {
    throw 'Business traffic may invoke Payments. Pass -AcknowledgeSafePaymentGateway only after manually confirming that Payments uses a local/test gateway rather than real Stripe.'
}

$ordersMode = if ($moneyPathEnabled) { 'MoneyPath' } else { 'ReadOnly' }
$internalMode = if ($perServiceBusinessEnabled) { 'Business' } else { 'Readiness' }
$ordersToken = if ($OrdersAccessToken) { $OrdersAccessToken } else { $AccessToken }
$inventoryToken = if ($InventoryAccessToken) { $InventoryAccessToken } else { $AccessToken }
$paymentsToken = if ($PaymentsAccessToken) { $PaymentsAccessToken } else { $AccessToken }

$scripts = [System.Collections.Generic.List[object]]::new()
$scripts.Add(@{
    Name = 'frontend'
    File = 'Invoke-FrontendTraffic.ps1'
    Args = @{
        Target = $FrontendTarget
        BaseUrl = $FrontendBaseUrl
        PortForwardServiceName = $FrontendPortForwardServiceName
        ExpectedPublicHost = $ExpectedPublicHost
        ExpectedPublicScheme = $ExpectedPublicScheme
        ExpectedPublicPort = $ExpectedPublicPort
        KubernetesNamespace = $KubernetesNamespace
        RequestsPerMinute = $FrontendRequestsPerMinute
    }
})
$scripts.Add(@{
    Name = 'catalog'
    File = 'Invoke-CatalogTraffic.ps1'
    Args = @{
        Target = $CatalogTarget
        BaseUrl = $CatalogBaseUrl
        PortForwardServiceName = $CatalogPortForwardServiceName
        ExpectedPublicHost = $ExpectedPublicHost
        ExpectedPublicScheme = $ExpectedPublicScheme
        ExpectedPublicPort = $ExpectedPublicPort
        KubernetesNamespace = $KubernetesNamespace
        RequestsPerMinute = $CatalogRequestsPerMinute
    }
})
$scripts.Add(@{
    Name = 'orders'
    File = 'Invoke-OrdersTraffic.ps1'
    Args = @{
        Target = $OrdersTarget
        BaseUrl = $OrdersBaseUrl
        PortForwardServiceName = $OrdersPortForwardServiceName
        ExpectedPublicHost = $ExpectedPublicHost
        ExpectedPublicScheme = $ExpectedPublicScheme
        ExpectedPublicPort = $ExpectedPublicPort
        KubernetesNamespace = $KubernetesNamespace
        Mode = $ordersMode
        AccessToken = $ordersToken
        AcknowledgeMoneyPathSideEffects = $moneyPathEnabled
        AcknowledgeSafePaymentGateway = [bool]$AcknowledgeSafePaymentGateway
        CatalogTarget = $CatalogTarget
        CatalogBaseUrl = $CatalogBaseUrl
        CatalogPortForwardServiceName = $CatalogPortForwardServiceName
        TrainId = $OrdersTrainId
        SeatClass = $OrdersSeatClass
        ExpectedOutcome = $OrdersExpectedOutcome
        RequestsPerMinute = if ($moneyPathEnabled) { [Math]::Min(10, $OrdersRequestsPerMinute) } else { $OrdersRequestsPerMinute }
    }
})
$scripts.Add(@{
    Name = 'inventory'
    File = 'Invoke-InventoryTraffic.ps1'
    Args = @{
        Target = $InventoryTarget
        BaseUrl = $InventoryBaseUrl
        PortForwardServiceName = $InventoryPortForwardServiceName
        ExpectedPublicHost = $ExpectedPublicHost
        ExpectedPublicScheme = $ExpectedPublicScheme
        ExpectedPublicPort = $ExpectedPublicPort
        KubernetesNamespace = $KubernetesNamespace
        Mode = $internalMode
        AccessToken = $inventoryToken
        AcknowledgeSeatConsumption = $perServiceBusinessEnabled
        CatalogTarget = $CatalogTarget
        CatalogBaseUrl = $CatalogBaseUrl
        CatalogPortForwardServiceName = $CatalogPortForwardServiceName
        RequestsPerMinute = if ($perServiceBusinessEnabled) { [Math]::Min(10, $InventoryRequestsPerMinute) } else { $InventoryRequestsPerMinute }
    }
})
$scripts.Add(@{
    Name = 'payments'
    File = 'Invoke-PaymentsTraffic.ps1'
    Args = @{
        Target = $PaymentsTarget
        BaseUrl = $PaymentsBaseUrl
        PortForwardServiceName = $PaymentsPortForwardServiceName
        KubernetesNamespace = $KubernetesNamespace
        Mode = $internalMode
        AccessToken = $paymentsToken
        # This is deliberately tied only to the explicit orchestrator switch.
        AcknowledgeSafeGatewayConfiguration = [bool]$AcknowledgeSafePaymentGateway
        RequestsPerMinute = if ($perServiceBusinessEnabled) { [Math]::Min(10, $PaymentsRequestsPerMinute) } else { $PaymentsRequestsPerMinute }
    }
})
if ($IncludeGatewaySimulator) {
    $scripts.Add(@{
        Name = 'payment-gateway-sim'
        File = 'Invoke-PaymentGatewaySimTraffic.ps1'
        Args = @{
            Target = $GatewayTarget
            BaseUrl = $GatewayBaseUrl
            PortForwardServiceName = $GatewayPortForwardServiceName
            KubernetesNamespace = $KubernetesNamespace
            Mode = $GatewayMode
            RequestsPerMinute = $GatewayRequestsPerMinute
        }
    })
}

$jobs = [System.Collections.Generic.List[object]]::new()
$summaries = @()
$jobErrors = @()
$unexpectedOutput = @()
$workerFailed = $false

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
        traffic_profile = $TrafficProfile
        traffic_mode = $TrafficProfile
        application_traffic = @($summaries | Where-Object application_traffic).Count -gt 0
        business_traffic = $moneyPathEnabled -or $perServiceBusinessEnabled
        money_path_enabled = $moneyPathEnabled
        direct_downstream_business_enabled = $perServiceBusinessEnabled
        combined_side_effects = $TrafficProfile -eq 'CombinedExplicit'
        gateway_simulator_included = [bool]$IncludeGatewaySimulator
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
        throw "One or more traffic workers failed. profile=$TrafficProfile, summaries=$($summaries.Count)/$($scripts.Count), errors=$($jobErrors.Count), unexpectedOutput=$($unexpectedOutput.Count)."
    }
}
finally {
    if ($jobs.Count -gt 0) {
        Stop-EuroTransitJobs -Jobs $jobs
    }
}
