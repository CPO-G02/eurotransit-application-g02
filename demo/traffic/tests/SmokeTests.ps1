[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$trafficDirectory = Split-Path -Parent $PSScriptRoot
$repositoryRoot = Split-Path -Parent (Split-Path -Parent $trafficDirectory)
. (Join-Path $trafficDirectory 'Common.ps1')

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Assert-Throws {
    param([scriptblock]$Action, [string]$Pattern)
    try {
        & $Action
        throw "Expected an exception matching '$Pattern'."
    }
    catch {
        if ($_.Exception.Message -notmatch $Pattern) { throw }
    }
}

function Assert-FileContains {
    param([string]$RelativePath, [string[]]$Patterns)
    $path = Join-Path $repositoryRoot $RelativePath
    $content = Get-Content -LiteralPath $path -Raw
    foreach ($pattern in $Patterns) {
        Assert-True ($content -match $pattern) "Contract source '$RelativePath' is missing '$pattern'."
    }
}

function New-OrchestratorParameters {
    param([string]$BaseUrl, [string]$OutputDirectory)
    return @{
        DurationSeconds = 1
        FrontendTarget = 'Stable'
        FrontendBaseUrl = $BaseUrl
        FrontendPortForwardServiceName = 'eurotransit-frontend'
        FrontendRequestsPerMinute = 600
        CatalogTarget = 'Stable'
        CatalogBaseUrl = $BaseUrl
        CatalogPortForwardServiceName = 'eurotransit-catalog'
        CatalogRequestsPerMinute = 600
        OrdersTarget = 'Stable'
        OrdersBaseUrl = $BaseUrl
        OrdersPortForwardServiceName = 'eurotransit-orders'
        OrdersRequestsPerMinute = 600
        InventoryTarget = 'Active'
        InventoryBaseUrl = $BaseUrl
        InventoryPortForwardServiceName = 'eurotransit-inventory'
        InventoryRequestsPerMinute = 600
        PaymentsTarget = 'Active'
        PaymentsBaseUrl = $BaseUrl
        PaymentsPortForwardServiceName = 'eurotransit-payments'
        PaymentsRequestsPerMinute = 600
        OrdersAccessToken = 'orders-token'
        InventoryAccessToken = 'inventory-token'
        PaymentsAccessToken = 'payments-token'
        OutputDirectory = $OutputDirectory
    }
}

# Contract-level guard: the fixture and scripts must continue to match the real
# controller paths and wire DTO fields. This does not start Spring services.
Assert-FileContains 'backend/orders/src/main/kotlin/it/polito/eurotransit/orders/dto/OrderAPIDTOs.kt' @(
    'data class OrderRequest',
    'idempotency_key',
    'user_email',
    'data class OrderStatusResponse',
    'transaction_id',
    'confirmed_at'
)
Assert-FileContains 'backend/orders/src/main/kotlin/it/polito/eurotransit/orders/controllers/OrderController.kt' @(
    '@RequestMapping\("/api/v1/orders"\)',
    '@PostMapping',
    '@GetMapping\("/\{orderId\}"\)'
)
Assert-FileContains 'backend/catalog/src/main/kotlin/it/polito/eurotransit/catalog/dto/CatalogDtos.kt' @(
    'data class ProductsResponse',
    'train_id',
    'seat_classes',
    'available'
)
Assert-FileContains 'backend/inventory/src/main/kotlin/it/polito/eurotransit/inventory/dto/InventoryDtos.kt' @(
    'data class ReserveRequest',
    'idempotency_key',
    'reservation_id',
    'INSUFFICIENT'
)
Assert-FileContains 'backend/inventory/src/main/kotlin/it/polito/eurotransit/inventory/controllers/InventoryController.kt' @(
    '@PostMapping\("/reserve"\)'
)
Assert-FileContains 'backend/payments/src/main/kotlin/it/polito/eurotransit/payments/dto/PaymentsDtos.kt' @(
    'data class AuthorizeRequest',
    'transaction_id',
    'data class DeclinedResponse',
    'reason'
)
Assert-FileContains 'backend/payments/src/main/kotlin/it/polito/eurotransit/payments/controllers/PaymentsController.kt' @(
    '@RequestMapping\("/api/v1/payments"\)',
    '@PostMapping\("/authorize"\)'
)
Assert-FileContains 'backend/payment-gateway-sim/src/main/kotlin/it/polito/eurotransit/paymentgateway/controllers/GatewayController.kt' @(
    '@PostMapping\("/gateway/charge"\)',
    'X-Simulate-Delay-Ms',
    'X-Simulate-Failure'
)

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
$listener.Stop()
$baseUrl = "http://127.0.0.1:$port"
$serverPath = Join-Path $PSScriptRoot 'mock_eurotransit_server.py'
$python = (Get-Command python -ErrorAction Stop).Source
$startParameters = @{
    FilePath = $python
    ArgumentList = @($serverPath, $port)
    PassThru = $true
}
if ($IsWindows) { $startParameters.WindowStyle = 'Hidden' }
$server = Start-Process @startParameters
$outputDirectory = Join-Path $trafficDirectory "results/smoke-$([guid]::NewGuid())"
$savedEnvironment = @{
    Generic = $env:EUROTRANSIT_ACCESS_TOKEN
    Orders = $env:EUROTRANSIT_ORDERS_ACCESS_TOKEN
    Inventory = $env:EUROTRANSIT_INVENTORY_ACCESS_TOKEN
    Payments = $env:EUROTRANSIT_PAYMENTS_ACCESS_TOKEN
}

try {
    $ready = $false
    for ($attempt = 0; $attempt -lt 50; $attempt++) {
        $probe = Invoke-EuroTransitHttpRequest -Uri ([uri]"$baseUrl/") -TimeoutSeconds 1
        if ($probe.StatusCode -eq 200) { $ready = $true; break }
        Start-Sleep -Milliseconds 100
    }
    Assert-True $ready 'Mock EuroTransit server did not start.'

    # Public target validation is strict by default and reports alternate
    # environments separately from the official Traefik route.
    $officialPublic = Resolve-EuroTransitDestination -Service catalog -Target Public
    Assert-True ($officialPublic.DestinationService -eq 'traefik-public-route') 'Official public host was not identified as the Traefik route.'
    Assert-Throws {
        Resolve-EuroTransitDestination -Service catalog -Target Public -BaseUrl 'http://127.0.0.1:8080'
    } 'must resolve through the public ingress'
    Assert-Throws {
        Resolve-EuroTransitDestination -Service catalog -Target Public -BaseUrl 'https://example.com'
    } 'expects host'
    $customPublic = Resolve-EuroTransitDestination -Service catalog -Target Public `
        -BaseUrl 'https://example.com' -ExpectedPublicHost 'example.com'
    Assert-True ($customPublic.DestinationService -eq 'configured-public-route') 'Explicit custom public host was incorrectly reported as the official Traefik route.'

    # Service DNS accepts only exact Kubernetes forms in the configured namespace.
    foreach ($hostName in @(
        'eurotransit-catalog',
        'eurotransit-catalog.eurotransit',
        'eurotransit-catalog.eurotransit.svc',
        'eurotransit-catalog.eurotransit.svc.cluster.local'
    )) {
        $resolved = Resolve-EuroTransitDestination -Service catalog -Target Stable -BaseUrl "http://${hostName}:8080"
        Assert-True ($resolved.Validation -eq 'service-dns') "Allowed Kubernetes DNS '$hostName' was rejected."
    }
    Assert-Throws {
        Resolve-EuroTransitDestination -Service catalog -Target Stable -BaseUrl 'http://eurotransit-catalog.example.com:8080'
    } 'expects Kubernetes Service DNS'
    Assert-Throws {
        Resolve-EuroTransitDestination -Service catalog -Target Stable -BaseUrl 'http://catalog.example.com:8080'
    } 'expects Kubernetes Service DNS'
    Assert-Throws {
        Resolve-EuroTransitDestination -Service catalog -Target Stable -BaseUrl 'http://eurotransit-inventory:8080'
    } 'expects Kubernetes Service DNS'
    Assert-Throws {
        Resolve-EuroTransitDestination -Service catalog -Target Canary `
            -BaseUrl $baseUrl -PortForwardServiceName eurotransit-catalog
    } 'expects port-forward service'
    Assert-Throws {
        Resolve-EuroTransitDestination -Service inventory -Target Preview
    } 'requires an explicit -BaseUrl'

    foreach ($status in @(200, 401, 403, 404, 409, 500, 503)) {
        $response = Invoke-EuroTransitHttpRequest -Uri ([uri]"$baseUrl/status/$status")
        Assert-True ($response.StatusCode -eq $status) "Expected HTTP $status, got $($response.StatusCode)."
    }
    $timeout = Invoke-EuroTransitHttpRequest -Uri ([uri]"$baseUrl/delay/1500") -TimeoutSeconds 1
    Assert-True ($timeout.ErrorType -eq 'timeout') "Expected timeout classification, got '$($timeout.ErrorType)'."

    $env:EUROTRANSIT_ACCESS_TOKEN = $null
    $env:EUROTRANSIT_ORDERS_ACCESS_TOKEN = $null
    $env:EUROTRANSIT_INVENTORY_ACCESS_TOKEN = $null
    $env:EUROTRANSIT_PAYMENTS_ACCESS_TOKEN = $null
    Assert-Throws {
        & (Join-Path $trafficDirectory 'Invoke-OrdersTraffic.ps1') `
            -Target Stable -BaseUrl $baseUrl -PortForwardServiceName eurotransit-orders `
            -DurationSeconds 1 -OutputDirectory $outputDirectory
    } 'requires -AccessToken'

    $ordersUnauthorized = & (Join-Path $trafficDirectory 'Invoke-OrdersTraffic.ps1') `
        -Target Stable -BaseUrl $baseUrl -PortForwardServiceName eurotransit-orders `
        -AccessToken inventory-token -DurationSeconds 1 -RequestsPerMinute 600 `
        -OutputDirectory $outputDirectory
    Assert-True (-not $ordersUnauthorized.passed -and $ordersUnauthorized.status_codes.'401' -ge 1) 'Wrong-audience Orders token did not produce 401.'
    $ordersForbidden = & (Join-Path $trafficDirectory 'Invoke-OrdersTraffic.ps1') `
        -Target Stable -BaseUrl $baseUrl -PortForwardServiceName eurotransit-orders `
        -AccessToken forbidden -DurationSeconds 1 -RequestsPerMinute 600 `
        -OutputDirectory $outputDirectory
    Assert-True (-not $ordersForbidden.passed -and $ordersForbidden.status_codes.'403' -ge 1) 'Forbidden Orders token did not produce 403.'

    # Service-specific environment variables are preferred over the generic
    # compatibility fallback and are never included in summaries.
    $env:EUROTRANSIT_ACCESS_TOKEN = 'wrong-generic-token'
    $env:EUROTRANSIT_ORDERS_ACCESS_TOKEN = 'orders-token'
    $env:EUROTRANSIT_INVENTORY_ACCESS_TOKEN = 'inventory-token'
    $env:EUROTRANSIT_PAYMENTS_ACCESS_TOKEN = 'payments-token'

    $frontend = & (Join-Path $trafficDirectory 'Invoke-FrontendTraffic.ps1') `
        -Target Stable -BaseUrl $baseUrl -PortForwardServiceName eurotransit-frontend `
        -DurationSeconds 1 -RequestsPerMinute 600 -OutputDirectory $outputDirectory
    Assert-True ($frontend.PSObject.TypeNames -contains 'EuroTransit.TrafficSummary') 'Frontend did not emit a structured summary.'
    Assert-True $frontend.passed 'Frontend smoke traffic failed.'

    $catalog = & (Join-Path $trafficDirectory 'Invoke-CatalogTraffic.ps1') `
        -Target Stable -BaseUrl $baseUrl -PortForwardServiceName eurotransit-catalog `
        -DurationSeconds 1 -RequestsPerMinute 600 -OutputDirectory $outputDirectory
    Assert-True $catalog.passed 'Catalog smoke traffic failed.'

    $ordersConfirmed = & (Join-Path $trafficDirectory 'Invoke-OrdersTraffic.ps1') `
        -Target Stable -BaseUrl $baseUrl -PortForwardServiceName eurotransit-orders `
        -CatalogTarget Stable -CatalogBaseUrl $baseUrl `
        -CatalogPortForwardServiceName eurotransit-catalog -Mode MoneyPath `
        -AcknowledgeMoneyPathSideEffects -AcknowledgeSafePaymentGateway `
        -MaxNewOperations 1 -DuplicatePercentage 100 -PollIntervalSeconds 0.1 `
        -DurationSeconds 2 -RequestsPerMinute 10 -OutputDirectory $outputDirectory
    Assert-True $ordersConfirmed.passed 'Orders confirmed money-path smoke traffic failed.'
    Assert-True ($ordersConfirmed.confirmed -eq 1) 'Orders did not reach CONFIRMED.'
    Assert-True ($ordersConfirmed.duplicate_attempts -eq 1 -and $ordersConfirmed.idempotency_mismatches -eq 0) 'Orders duplicate replay was not stable.'

    $ordersInsufficient = & (Join-Path $trafficDirectory 'Invoke-OrdersTraffic.ps1') `
        -Target Stable -BaseUrl $baseUrl -PortForwardServiceName eurotransit-orders `
        -CatalogTarget Stable -CatalogBaseUrl $baseUrl `
        -CatalogPortForwardServiceName eurotransit-catalog -Mode MoneyPath `
        -TrainId FULL -SeatClass standard -ExpectedOutcome InsufficientInventory `
        -AcknowledgeMoneyPathSideEffects -AcknowledgeSafePaymentGateway `
        -MaxNewOperations 1 -DuplicatePercentage 0 -PollIntervalSeconds 0.1 `
        -DurationSeconds 2 -RequestsPerMinute 10 -OutputDirectory $outputDirectory
    Assert-True ($ordersInsufficient.passed -and $ordersInsufficient.failed_insufficient_inventory -eq 1) 'Expected Inventory business failure was not classified.'

    $ordersDeclined = & (Join-Path $trafficDirectory 'Invoke-OrdersTraffic.ps1') `
        -Target Stable -BaseUrl $baseUrl -PortForwardServiceName eurotransit-orders `
        -CatalogTarget Stable -CatalogBaseUrl $baseUrl `
        -CatalogPortForwardServiceName eurotransit-catalog -Mode MoneyPath `
        -TrainId DECLINE -SeatClass standard -ExpectedOutcome PaymentDeclined `
        -AcknowledgeMoneyPathSideEffects -AcknowledgeSafePaymentGateway `
        -MaxNewOperations 1 -DuplicatePercentage 0 -PollIntervalSeconds 0.1 `
        -DurationSeconds 2 -RequestsPerMinute 10 -OutputDirectory $outputDirectory
    Assert-True ($ordersDeclined.passed -and $ordersDeclined.failed_payment_declined -eq 1) 'Expected payment decline was not classified.'
    Assert-True ($ordersDeclined.failure_classification_source -match 'operator-expected') 'Payment decline classification did not disclose its source.'

    $ordersTimeout = & (Join-Path $trafficDirectory 'Invoke-OrdersTraffic.ps1') `
        -Target Stable -BaseUrl $baseUrl -PortForwardServiceName eurotransit-orders `
        -CatalogTarget Stable -CatalogBaseUrl $baseUrl `
        -CatalogPortForwardServiceName eurotransit-catalog -Mode MoneyPath `
        -TrainId TIMEOUT -SeatClass standard -ExpectedOutcome Confirmed `
        -AcknowledgeMoneyPathSideEffects -AcknowledgeSafePaymentGateway `
        -MaxNewOperations 1 -DuplicatePercentage 0 -PollTimeoutSeconds 1 `
        -PollIntervalSeconds 0.1 -DurationSeconds 2 -RequestsPerMinute 10 `
        -OutputDirectory $outputDirectory
    Assert-True (-not $ordersTimeout.passed -and $ordersTimeout.pending_or_timeout -eq 1) 'Orders timeout was not reported.'

    $inventory = & (Join-Path $trafficDirectory 'Invoke-InventoryTraffic.ps1') `
        -Target Active -BaseUrl $baseUrl -PortForwardServiceName eurotransit-inventory `
        -Mode Business -AcknowledgeSeatConsumption `
        -TrainId TR-SMOKE-001 -SeatClass standard -MaxNewOperations 1 `
        -DuplicatePercentage 100 -DurationSeconds 7 -RequestsPerMinute 10 `
        -OutputDirectory $outputDirectory
    Assert-True $inventory.passed 'Inventory business smoke traffic failed.'
    Assert-True ($inventory.duplicate_attempts -ge 1 -and $inventory.idempotency_mismatches -eq 0) 'Inventory duplicate replay was not stable.'

    $payments = & (Join-Path $trafficDirectory 'Invoke-PaymentsTraffic.ps1') `
        -Target Active -BaseUrl $baseUrl -PortForwardServiceName eurotransit-payments `
        -Mode Business -AcknowledgeSafeGatewayConfiguration `
        -MaxNewOperations 1 -DuplicatePercentage 100 -DurationSeconds 7 `
        -RequestsPerMinute 10 -OutputDirectory $outputDirectory
    Assert-True $payments.passed 'Payments business smoke traffic failed.'
    Assert-True ($payments.duplicate_attempts -ge 1 -and $payments.idempotency_mismatches -eq 0) 'Payments duplicate replay was not stable.'

    foreach ($mode in @('Success', 'Latency')) {
        $gateway = & (Join-Path $trafficDirectory 'Invoke-PaymentGatewaySimTraffic.ps1') `
            -Target Active -BaseUrl $baseUrl `
            -PortForwardServiceName eurotransit-payment-gateway-sim -Mode $mode `
            -LatencyMilliseconds 50 -DurationSeconds 1 -RequestsPerMinute 10 `
            -OutputDirectory $outputDirectory
        Assert-True $gateway.passed "Gateway $mode smoke traffic failed."
    }
    Assert-Throws {
        & (Join-Path $trafficDirectory 'Invoke-PaymentGatewaySimTraffic.ps1') `
            -Target Active -BaseUrl $baseUrl `
            -PortForwardServiceName eurotransit-payment-gateway-sim -Mode Failure `
            -DurationSeconds 1 -OutputDirectory $outputDirectory
    } 'Pass -AllowFailureMode'
    $gatewayFailure = & (Join-Path $trafficDirectory 'Invoke-PaymentGatewaySimTraffic.ps1') `
        -Target Active -BaseUrl $baseUrl `
        -PortForwardServiceName eurotransit-payment-gateway-sim -Mode Failure `
        -AllowFailureMode -DurationSeconds 1 -RequestsPerMinute 10 `
        -OutputDirectory $outputDirectory
    Assert-True ($gatewayFailure.passed -and $gatewayFailure.status_codes.'503' -ge 1) 'Opt-in Gateway failure fixture did not receive the expected 503.'

    $orchestratorPath = Join-Path $trafficDirectory 'Run-AllServicesTraffic.ps1'
    $readOnlyParameters = New-OrchestratorParameters -BaseUrl $baseUrl -OutputDirectory $outputDirectory
    $readOnlyParameters.TrafficProfile = 'ReadOnly'
    $readOnly = & $orchestratorPath @readOnlyParameters
    Assert-True ($readOnly.PSObject.TypeNames -contains 'EuroTransit.OrchestratorSummary') 'ReadOnly orchestrator output was not structured.'
    Assert-True ($readOnly.passed -and $readOnly.traffic_profile -eq 'ReadOnly') 'ReadOnly orchestrator smoke run failed.'

    Assert-Throws {
        $unsafeMoneyPath = New-OrchestratorParameters -BaseUrl $baseUrl -OutputDirectory $outputDirectory
        $unsafeMoneyPath.TrafficProfile = 'MoneyPath'
        $unsafeMoneyPath.AcknowledgeMoneyPathSideEffects = $true
        & $orchestratorPath @unsafeMoneyPath
    } 'AcknowledgeSafePaymentGateway'

    $moneyPathParameters = New-OrchestratorParameters -BaseUrl $baseUrl -OutputDirectory $outputDirectory
    $moneyPathParameters.TrafficProfile = 'MoneyPath'
    $moneyPathParameters.DurationSeconds = 3
    $moneyPathParameters.AcknowledgeMoneyPathSideEffects = $true
    $moneyPathParameters.AcknowledgeSafePaymentGateway = $true
    $moneyPathParameters.OrdersRequestsPerMinute = 10
    $moneyPath = & $orchestratorPath @moneyPathParameters
    Assert-True ($moneyPath.passed -and $moneyPath.money_path_enabled -and -not $moneyPath.direct_downstream_business_enabled) 'MoneyPath orchestrator did not isolate downstream traffic.'

    $perServiceParameters = New-OrchestratorParameters -BaseUrl $baseUrl -OutputDirectory $outputDirectory
    $perServiceParameters.TrafficProfile = 'PerServiceBusiness'
    $perServiceParameters.AcknowledgePerServiceBusinessSideEffects = $true
    $perServiceParameters.AcknowledgeSafePaymentGateway = $true
    $perServiceParameters.InventoryRequestsPerMinute = 10
    $perServiceParameters.PaymentsRequestsPerMinute = 10
    $perService = & $orchestratorPath @perServiceParameters
    Assert-True ($perService.passed -and -not $perService.money_path_enabled -and $perService.direct_downstream_business_enabled) 'PerServiceBusiness orchestrator profile failed.'

    Assert-Throws {
        $combinedParameters = New-OrchestratorParameters -BaseUrl $baseUrl -OutputDirectory $outputDirectory
        $combinedParameters.TrafficProfile = 'CombinedExplicit'
        $combinedParameters.AcknowledgeMoneyPathSideEffects = $true
        $combinedParameters.AcknowledgePerServiceBusinessSideEffects = $true
        $combinedParameters.AcknowledgeSafePaymentGateway = $true
        & $orchestratorPath @combinedParameters
    } 'AcknowledgeCombinedSideEffects'

    Assert-True (@(Get-Job -Name 'eurotransit-*' -ErrorAction SilentlyContinue).Count -eq 0) 'Orchestrator leaked PowerShell jobs after normal completion.'
    $cleanupJobs = @(
        Start-Job -Name "eurotransit-cleanup-a-$([guid]::NewGuid())" -ScriptBlock { Start-Sleep -Seconds 30 }
        Start-Job -Name "eurotransit-cleanup-b-$([guid]::NewGuid())" -ScriptBlock { Start-Sleep -Seconds 30 }
    )
    $cleanupIds = @($cleanupJobs.Id)
    Stop-EuroTransitJobs -Jobs $cleanupJobs
    Assert-True (@(Get-Job | Where-Object Id -in $cleanupIds).Count -eq 0) 'Cleanup function left test jobs behind.'

    Write-Host 'EuroTransit traffic smoke tests passed.'
}
finally {
    Get-Job -Name 'eurotransit-*' -ErrorAction SilentlyContinue |
        Stop-Job -ErrorAction SilentlyContinue
    Get-Job -Name 'eurotransit-*' -ErrorAction SilentlyContinue |
        Remove-Job -Force -ErrorAction SilentlyContinue
    if ($server -and -not $server.HasExited) {
        Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
        $server.WaitForExit()
    }
    if (Test-Path -LiteralPath $outputDirectory) {
        Remove-Item -LiteralPath $outputDirectory -Recurse -Force
    }
    $env:EUROTRANSIT_ACCESS_TOKEN = $savedEnvironment.Generic
    $env:EUROTRANSIT_ORDERS_ACCESS_TOKEN = $savedEnvironment.Orders
    $env:EUROTRANSIT_INVENTORY_ACCESS_TOKEN = $savedEnvironment.Inventory
    $env:EUROTRANSIT_PAYMENTS_ACCESS_TOKEN = $savedEnvironment.Payments
}
