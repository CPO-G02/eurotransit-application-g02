[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$trafficDirectory = Split-Path -Parent $PSScriptRoot
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

try {
    $ready = $false
    for ($attempt = 0; $attempt -lt 50; $attempt++) {
        try {
            $probe = Invoke-EuroTransitHttpRequest -Uri ([uri]"$baseUrl/") -TimeoutSeconds 1
            if ($probe.StatusCode -eq 200) { $ready = $true; break }
        }
        catch {}
        Start-Sleep -Milliseconds 100
    }
    Assert-True $ready 'Mock EuroTransit server did not start.'

    Assert-Throws {
        Resolve-EuroTransitDestination -Service catalog -Target Canary `
            -BaseUrl $baseUrl -PortForwardServiceName eurotransit-catalog
    } 'expects port-forward service'
    Assert-Throws {
        Resolve-EuroTransitDestination -Service inventory -Target Preview
    } 'requires an explicit -BaseUrl'
    Assert-Throws {
        Resolve-EuroTransitDestination -Service payments -Target Active `
            -BaseUrl 'http://eurotransit-inventory.eurotransit.svc.cluster.local:8080'
    } 'expects destination host'

    foreach ($status in @(200, 401, 403, 404, 409, 500, 503)) {
        $response = Invoke-EuroTransitHttpRequest -Uri ([uri]"$baseUrl/status/$status")
        Assert-True ($response.StatusCode -eq $status) "Expected HTTP $status, got $($response.StatusCode)."
    }
    $timeout = Invoke-EuroTransitHttpRequest -Uri ([uri]"$baseUrl/delay/1500") -TimeoutSeconds 1
    Assert-True ($timeout.ErrorType -eq 'timeout') "Expected timeout classification, got '$($timeout.ErrorType)'."

    $frontend = & (Join-Path $trafficDirectory 'Invoke-FrontendTraffic.ps1') `
        -Target Stable -BaseUrl $baseUrl -PortForwardServiceName eurotransit-frontend `
        -DurationSeconds 1 -RequestsPerMinute 600 -OutputDirectory $outputDirectory
    Assert-True ($frontend.PSObject.TypeNames -contains 'EuroTransit.TrafficSummary') 'Frontend did not emit a structured summary.'
    Assert-True $frontend.passed 'Frontend smoke traffic failed.'

    $catalog = & (Join-Path $trafficDirectory 'Invoke-CatalogTraffic.ps1') `
        -Target Stable -BaseUrl $baseUrl -PortForwardServiceName eurotransit-catalog `
        -DurationSeconds 1 -RequestsPerMinute 600 -OutputDirectory $outputDirectory
    Assert-True $catalog.passed 'Catalog smoke traffic failed.'

    $orders = & (Join-Path $trafficDirectory 'Invoke-OrdersTraffic.ps1') `
        -Target Stable -BaseUrl $baseUrl -PortForwardServiceName eurotransit-orders `
        -CatalogTarget Stable -CatalogBaseUrl $baseUrl `
        -CatalogPortForwardServiceName eurotransit-catalog -Mode MoneyPath `
        -AccessToken smoke-token -AcknowledgeMoneyPathSideEffects `
        -MaxNewOperations 1 -DuplicatePercentage 100 -PollIntervalSeconds 0.1 `
        -DurationSeconds 3 -RequestsPerMinute 10 -OutputDirectory $outputDirectory
    Assert-True $orders.passed 'Orders money-path smoke traffic failed.'
    Assert-True ($orders.confirmed_orders -eq 1) 'Orders did not reach CONFIRMED.'
    Assert-True ($orders.duplicate_attempts -eq 1 -and $orders.idempotency_mismatches -eq 0) 'Orders duplicate replay was not stable.'

    $inventory = & (Join-Path $trafficDirectory 'Invoke-InventoryTraffic.ps1') `
        -Target Active -BaseUrl $baseUrl -PortForwardServiceName eurotransit-inventory `
        -Mode Business -AccessToken smoke-token -AcknowledgeSeatConsumption `
        -TrainId TR-SMOKE-001 -SeatClass standard -MaxNewOperations 1 `
        -DuplicatePercentage 100 -DurationSeconds 7 -RequestsPerMinute 10 `
        -OutputDirectory $outputDirectory
    Assert-True $inventory.passed 'Inventory business smoke traffic failed.'
    Assert-True ($inventory.duplicate_attempts -ge 1 -and $inventory.idempotency_mismatches -eq 0) 'Inventory duplicate replay was not stable.'

    $payments = & (Join-Path $trafficDirectory 'Invoke-PaymentsTraffic.ps1') `
        -Target Active -BaseUrl $baseUrl -PortForwardServiceName eurotransit-payments `
        -Mode Business -AccessToken smoke-token -AcknowledgeSafeGatewayConfiguration `
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
    $gatewayFailure = & (Join-Path $trafficDirectory 'Invoke-PaymentGatewaySimTraffic.ps1') `
        -Target Active -BaseUrl $baseUrl `
        -PortForwardServiceName eurotransit-payment-gateway-sim -Mode Failure `
        -AllowFailureMode -DurationSeconds 1 -RequestsPerMinute 10 `
        -OutputDirectory $outputDirectory
    Assert-True $gatewayFailure.passed 'Opt-in Gateway failure fixture did not receive the expected 503.'
    Assert-True ($gatewayFailure.status_codes.'503' -ge 1) 'Gateway failure fixture did not record HTTP 503.'

    $orchestrator = & (Join-Path $trafficDirectory 'Run-AllServicesTraffic.ps1') `
        -DurationSeconds 1 -AccessToken smoke-token `
        -FrontendTarget Stable -FrontendBaseUrl $baseUrl -FrontendPortForwardServiceName eurotransit-frontend -FrontendRequestsPerMinute 600 `
        -CatalogTarget Stable -CatalogBaseUrl $baseUrl -CatalogPortForwardServiceName eurotransit-catalog -CatalogRequestsPerMinute 600 `
        -OrdersTarget Stable -OrdersBaseUrl $baseUrl -OrdersPortForwardServiceName eurotransit-orders -OrdersRequestsPerMinute 600 `
        -InventoryTarget Active -InventoryBaseUrl $baseUrl -InventoryPortForwardServiceName eurotransit-inventory -InventoryRequestsPerMinute 600 `
        -PaymentsTarget Active -PaymentsBaseUrl $baseUrl -PaymentsPortForwardServiceName eurotransit-payments -PaymentsRequestsPerMinute 600 `
        -GatewayTarget Active -GatewayBaseUrl $baseUrl -GatewayPortForwardServiceName eurotransit-payment-gateway-sim -GatewayRequestsPerMinute 10 `
        -OutputDirectory $outputDirectory
    Assert-True ($orchestrator.PSObject.TypeNames -contains 'EuroTransit.OrchestratorSummary') 'Orchestrator output was not structured.'
    Assert-True $orchestrator.passed 'Orchestrator smoke run failed.'
    Assert-True (@(Get-Job -Name 'eurotransit-*' -ErrorAction SilentlyContinue).Count -eq 0) 'Orchestrator leaked PowerShell jobs.'

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
}
