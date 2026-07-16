[CmdletBinding()]
param(
    [ValidateSet('Public', 'Stable', 'Canary', 'Active', 'Preview')]
    [string]$Target = 'Public',
    [string]$BaseUrl,
    [string]$PortForwardServiceName,
    [ValidateSet('ReadOnly', 'MoneyPath')][string]$Mode = 'ReadOnly',
    [string]$AccessToken,
    [switch]$AcknowledgeMoneyPathSideEffects,
    [ValidateSet('Public', 'Stable', 'Canary', 'Active', 'Preview')]
    [string]$CatalogTarget = 'Public',
    [string]$CatalogBaseUrl,
    [string]$CatalogPortForwardServiceName,
    [ValidateRange(1, 20)][int]$Quantity = 1,
    [ValidateRange(1, 20)][int]$MaxNewOperations = 3,
    [ValidateRange(0, 100)][int]$DuplicatePercentage = 20,
    [ValidateRange(1, 120)][int]$PollTimeoutSeconds = 30,
    [ValidateRange(0.1, 30)][double]$PollIntervalSeconds = 1,
    [string]$UserId = 'demo-user',
    [string]$UserEmail = 'demo-user@example.invalid',
    [int]$DurationMinutes = 20,
    [int]$DurationSeconds = 0,
    [ValidateRange(1, 6000)][int]$RequestsPerMinute = 60,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'results')
)

. (Join-Path $PSScriptRoot 'Common.ps1')

$destination = Resolve-EuroTransitDestination -Service orders -Target $Target `
    -BaseUrl $BaseUrl -PortForwardServiceName $PortForwardServiceName
$token = Get-EuroTransitAccessToken $AccessToken
if (-not $token) {
    throw 'Orders traffic requires -AccessToken or EUROTRANSIT_ACCESS_TOKEN.'
}
$headers = @{ Authorization = "Bearer $token" }

if ($Mode -eq 'ReadOnly') {
    Invoke-EuroTransitTraffic -Service orders -Destination $destination `
        -Path '/api/v1/orders' -Headers $headers -DurationMinutes $DurationMinutes `
        -DurationSeconds $DurationSeconds -RequestsPerMinute $RequestsPerMinute `
        -TrafficMode 'read-only' -OutputDirectory $OutputDirectory
    return
}

if (-not $AcknowledgeMoneyPathSideEffects) {
    throw 'MoneyPath mode creates real orders, reserves seats, and invokes Payments. Pass -AcknowledgeMoneyPathSideEffects after verifying the environment is safe.'
}
if ($RequestsPerMinute -gt 10) {
    throw 'Orders MoneyPath traffic is capped at 10 new operations per minute.'
}

$catalogDestination = Resolve-EuroTransitDestination -Service catalog -Target $CatalogTarget `
    -BaseUrl $CatalogBaseUrl -PortForwardServiceName $CatalogPortForwardServiceName
$timing = Get-EuroTransitDeadline -DurationMinutes $DurationMinutes -DurationSeconds $DurationSeconds
$records = [System.Collections.Generic.List[object]]::new()
$endToEndLatencies = [System.Collections.Generic.List[double]]::new()
$baseDelayMs = 60000.0 / $RequestsPerMinute
$accepted = 0
$confirmed = 0
$failedOrders = 0
$pollTimeouts = 0
$duplicateAttempts = 0
$idempotencyMismatches = 0
$protocolFailures = 0
$catalogLookups = 0

try {
    while ((Get-Date) -lt $timing.Deadline -and $accepted -lt $MaxNewOperations) {
        $operationStarted = Get-Date
        $operationStopwatch = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            $selection = Get-EuroTransitCatalogSelection -Destination $catalogDestination -Quantity $Quantity
            $catalogLookups++
            $idempotencyKey = "demo-order-$([guid]::NewGuid())"
            $body = @{
                idempotency_key = $idempotencyKey
                user_id = $UserId
                user_email = $UserEmail
                train_id = $selection.train_id
                seat_class = $selection.seat_class
                quantity = $selection.quantity
                amount = $selection.amount
                currency = $selection.currency
            }
            $ordersUri = Join-EuroTransitUri -BaseUri $destination.BaseUri -Path '/api/v1/orders'
            $create = Invoke-EuroTransitHttpRequest -Uri $ordersUri -Method POST -Headers $headers -Body $body
            $createValid = $create.StatusCode -eq 202 -and $null -ne $create.Json -and $create.Json.order_id
            $records.Add([pscustomobject]@{
                service = 'orders'; target = $destination.Target; destination_service = $destination.DestinationService
                timestamp = $operationStarted.ToUniversalTime().ToString('o'); operation = 'create'
                method = 'POST'; uri = $ordersUri.AbsoluteUri; status_code = $create.StatusCode
                outcome = if ($createValid) { 'success' } else { 'failure' }
                error_type = if ($create.ErrorType) { $create.ErrorType } elseif (-not $createValid) { 'http-or-contract' } else { $null }
                latency_ms = $create.LatencyMs; idempotency_key = $idempotencyKey; operation_id = $null
            })
            if (-not $createValid) {
                $protocolFailures++
                continue
            }

            $accepted++
            $orderId = [string]$create.Json.order_id
            $records[$records.Count - 1].operation_id = $orderId

            if ((Get-Random -Minimum 0 -Maximum 100) -lt $DuplicatePercentage) {
                $duplicateAttempts++
                $duplicate = Invoke-EuroTransitHttpRequest -Uri $ordersUri -Method POST -Headers $headers -Body $body
                $duplicateId = if ($duplicate.Json) { [string]$duplicate.Json.order_id } else { $null }
                $duplicateValid = $duplicate.StatusCode -in @(202, 409) -and $duplicateId -eq $orderId
                if (-not $duplicateValid) {
                    $idempotencyMismatches++
                    $protocolFailures++
                }
                $records.Add([pscustomobject]@{
                    service = 'orders'; target = $destination.Target; destination_service = $destination.DestinationService
                    timestamp = (Get-Date).ToUniversalTime().ToString('o'); operation = 'duplicate-create'
                    method = 'POST'; uri = $ordersUri.AbsoluteUri; status_code = $duplicate.StatusCode
                    outcome = if ($duplicateValid) { 'success' } else { 'failure' }
                    error_type = if ($duplicate.ErrorType) { $duplicate.ErrorType } elseif (-not $duplicateValid) { 'idempotency' } else { $null }
                    latency_ms = $duplicate.LatencyMs; idempotency_key = $idempotencyKey; operation_id = $duplicateId
                })
            }

            $pollDeadline = (Get-Date).AddSeconds($PollTimeoutSeconds)
            $terminalStatus = $null
            while ((Get-Date) -lt $pollDeadline -and (Get-Date) -lt $timing.Deadline) {
                $pollUri = Join-EuroTransitUri -BaseUri $destination.BaseUri -Path "/api/v1/orders/$orderId"
                $poll = Invoke-EuroTransitHttpRequest -Uri $pollUri -Headers $headers
                $pollValid = $poll.StatusCode -eq 200 -and $null -ne $poll.Json -and $poll.Json.order_id -eq $orderId
                $records.Add([pscustomobject]@{
                    service = 'orders'; target = $destination.Target; destination_service = $destination.DestinationService
                    timestamp = (Get-Date).ToUniversalTime().ToString('o'); operation = 'poll'
                    method = 'GET'; uri = $pollUri.AbsoluteUri; status_code = $poll.StatusCode
                    outcome = if ($pollValid) { 'success' } else { 'failure' }
                    error_type = if ($poll.ErrorType) { $poll.ErrorType } elseif (-not $pollValid) { 'http-or-contract' } else { $null }
                    latency_ms = $poll.LatencyMs; idempotency_key = $idempotencyKey; operation_id = $orderId
                })
                if (-not $pollValid) {
                    $protocolFailures++
                    break
                }
                $status = [string]$poll.Json.status
                if ($status -in @('CONFIRMED', 'FAILED')) {
                    $terminalStatus = $status
                    break
                }
                $remainingPollMs = [Math]::Min(
                    ($pollDeadline - (Get-Date)).TotalMilliseconds,
                    ($timing.Deadline - (Get-Date)).TotalMilliseconds
                )
                if ($remainingPollMs -gt 0) {
                    Start-Sleep -Milliseconds ([int][Math]::Min($remainingPollMs, $PollIntervalSeconds * 1000))
                }
            }

            $operationStopwatch.Stop()
            $endToEndLatencies.Add($operationStopwatch.Elapsed.TotalMilliseconds)
            switch ($terminalStatus) {
                'CONFIRMED' { $confirmed++ }
                'FAILED' { $failedOrders++ }
                default { $pollTimeouts++ }
            }
        }
        catch {
            $protocolFailures++
            $records.Add([pscustomobject]@{
                service = 'orders'; target = $destination.Target; destination_service = $destination.DestinationService
                timestamp = (Get-Date).ToUniversalTime().ToString('o'); operation = 'money-path'
                method = 'MULTI'; uri = $destination.BaseUri.AbsoluteUri; status_code = $null
                outcome = 'failure'; error_type = 'script'; latency_ms = $operationStopwatch.Elapsed.TotalMilliseconds
                idempotency_key = $null; operation_id = $null
            })
        }
        finally {
            if ($operationStopwatch.IsRunning) { $operationStopwatch.Stop() }
            Start-EuroTransitRateDelay -Deadline $timing.Deadline -BaseDelayMs $baseDelayMs -ElapsedMs $operationStopwatch.Elapsed.TotalMilliseconds
        }
    }
}
finally {
    $passed = $accepted -gt 0 -and $confirmed -eq $accepted -and $failedOrders -eq 0 `
        -and $pollTimeouts -eq 0 -and $idempotencyMismatches -eq 0 -and $protocolFailures -eq 0
    $summary = New-EuroTransitTrafficSummary -Service orders -Destination $destination `
        -TrafficMode 'money-path' -Started $timing.Started -Finished (Get-Date) -Records $records `
        -Passed $passed -AdditionalFields @{
            catalog_target = $catalogDestination.Target
            catalog_destination_service = $catalogDestination.DestinationService
            catalog_lookups = $catalogLookups
            new_operations = $accepted
            confirmed_orders = $confirmed
            failed_orders = $failedOrders
            pending_or_timed_out_orders = $pollTimeouts
            duplicate_attempts = $duplicateAttempts
            idempotency_mismatches = $idempotencyMismatches
            protocol_failures = $protocolFailures
            average_end_to_end_ms = if ($endToEndLatencies.Count) { [Math]::Round(($endToEndLatencies | Measure-Object -Average).Average, 2) } else { 0 }
            p95_end_to_end_ms = Get-Percentile $endToEndLatencies 95
        }
    Write-EuroTransitResults -Records $records -Summary $summary -OutputDirectory $OutputDirectory -Started $timing.Started
    $summary
}
