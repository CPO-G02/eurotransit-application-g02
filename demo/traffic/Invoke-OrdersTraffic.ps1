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
    [ValidateSet('ReadOnly', 'MoneyPath', 'Rollout')][string]$Mode = 'ReadOnly',
    [string]$AccessToken,
    [switch]$AcknowledgeMoneyPathSideEffects,
    [switch]$AcknowledgeSafePaymentGateway,
    [ValidateSet('Public', 'Stable', 'Canary', 'Active', 'Preview')]
    [string]$CatalogTarget = 'Public',
    [string]$CatalogBaseUrl,
    [string]$CatalogPortForwardServiceName,
    [string]$TrainId,
    [string]$SeatClass,
    [ValidateSet('Confirmed', 'InsufficientInventory', 'PaymentDeclined')]
    [string]$ExpectedOutcome = 'Confirmed',
    [ValidateRange(1, 20)][int]$Quantity = 1,
    [ValidateRange(1, 10000)][int]$MaxNewOperations = 3,
    [ValidateRange(0, 100)][int]$DuplicatePercentage = 20,
    [ValidateRange(1, 120)][int]$PollTimeoutSeconds = 30,
    [ValidateRange(0.1, 30)][double]$PollIntervalSeconds = 1,
    [string]$UserId = 'demo-user',
    [string]$UserEmail = 'demo-user@example.invalid',
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

# Rollout traffic must exercise candidate-local order creation. Its defaults are
# deliberately lower than the generic rollout profile because every POST starts
# a real saga. Explicit command-line values continue to win.
$effectiveMode = if ($Profile -eq 'Rollout' -and -not $boundParameters.ContainsKey('Mode')) { 'Rollout' } else { $Mode }
$effectiveTarget = if ($effectiveMode -eq 'Rollout' -and -not $boundParameters.ContainsKey('Target')) { 'Canary' } else { $Target }
if ($Profile -eq 'Rollout' -and -not $boundParameters.ContainsKey('RequestsPerMinute')) {
    $settings.RequestsPerMinute = 2
}
$plannedDurationSeconds = if ($settings.DurationSeconds -gt 0) {
    $settings.DurationSeconds
} else {
    $settings.DurationMinutes * 60
}
$scheduledNewOperations = [Math]::Max(1, [Math]::Floor($plannedDurationSeconds * $settings.RequestsPerMinute / 60.0))
$operationBudget = if ($effectiveMode -eq 'Rollout' -and -not $boundParameters.ContainsKey('MaxNewOperations')) {
    $scheduledNewOperations
} else {
    $MaxNewOperations
}
$expectedNewOperations = $operationBudget
$minimumRequiredNewOperations = [Math]::Ceiling(
    $operationBudget * $settings.MinimumRequestVolumePercentage / 100.0
)

$destination = Resolve-EuroTransitDestination -Service orders -Target $effectiveTarget `
    -BaseUrl $BaseUrl -PortForwardServiceName $PortForwardServiceName `
    -ExpectedPublicHost $ExpectedPublicHost -ExpectedPublicScheme $ExpectedPublicScheme `
    -ExpectedPublicPort $ExpectedPublicPort -KubernetesNamespace $KubernetesNamespace
$token = Get-EuroTransitAccessToken -Service orders -AccessToken $AccessToken
if (-not $token) {
    throw 'Orders traffic requires -AccessToken, EUROTRANSIT_ORDERS_ACCESS_TOKEN, or the explicit EUROTRANSIT_ACCESS_TOKEN compatibility fallback.'
}
$headers = @{ Authorization = "Bearer $token" }

if ($effectiveMode -eq 'ReadOnly') {
    Invoke-EuroTransitTraffic -Service orders -Destination $destination `
        -Path '/api/v1/orders' -Headers $headers -DurationMinutes $settings.DurationMinutes `
        -DurationSeconds $settings.DurationSeconds -RequestsPerMinute $settings.RequestsPerMinute `
        -TimeoutSeconds $settings.TimeoutSeconds -MaxConcurrency $settings.MaxConcurrency `
        -MinimumRequestVolumePercentage $settings.MinimumRequestVolumePercentage `
        -TrafficMode 'read-only' -OutputDirectory $OutputDirectory
    return
}

if (-not $AcknowledgeMoneyPathSideEffects) {
    throw 'MoneyPath mode creates real orders and reserves seats. Pass -AcknowledgeMoneyPathSideEffects only in a controlled environment.'
}
if (-not $AcknowledgeSafePaymentGateway) {
    throw 'MoneyPath invokes Payments. Pass -AcknowledgeSafePaymentGateway only after manually confirming that Payments uses a local/test gateway rather than real Stripe.'
}
if ($settings.RequestsPerMinute -gt 10) {
    throw 'Orders MoneyPath and Rollout traffic are capped at 10 new operations per minute.'
}
if ([bool]$TrainId -xor [bool]$SeatClass) {
    throw 'Specify both -TrainId and -SeatClass, or neither.'
}

$catalogDestination = Resolve-EuroTransitDestination -Service catalog -Target $CatalogTarget `
    -BaseUrl $CatalogBaseUrl -PortForwardServiceName $CatalogPortForwardServiceName `
    -ExpectedPublicHost $ExpectedPublicHost -ExpectedPublicScheme $ExpectedPublicScheme `
    -ExpectedPublicPort $ExpectedPublicPort -KubernetesNamespace $KubernetesNamespace
$timing = Get-EuroTransitDeadline -DurationMinutes $settings.DurationMinutes -DurationSeconds $settings.DurationSeconds
$records = [System.Collections.Generic.List[object]]::new()
$endToEndLatencies = [System.Collections.Generic.List[double]]::new()
$statusSequences = [System.Collections.Generic.List[string]]::new()
$baseDelayMs = 60000.0 / $settings.RequestsPerMinute
$accepted = 0
$confirmed = 0
$failedInsufficientInventory = 0
$failedPaymentDeclined = 0
$failedUnclassifiedBusiness = 0
$failedTechnical = 0
$pendingOrTimeout = 0
$duplicateAttempts = 0
$idempotencyMismatches = 0
$catalogLookups = 0

try {
    while ((Get-Date) -lt $timing.Deadline -and $accepted -lt $operationBudget) {
        $operationStarted = Get-Date
        $operationStopwatch = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            $selection = Get-EuroTransitCatalogSelection -Destination $catalogDestination `
                -Quantity $Quantity -TrainId $TrainId -SeatClass $SeatClass `
                -TimeoutSeconds $settings.TimeoutSeconds
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
            $create = Invoke-EuroTransitHttpRequest -Uri $ordersUri -Method POST -Headers $headers -Body $body -TimeoutSeconds $settings.TimeoutSeconds
            $createValid = $create.StatusCode -eq 202 -and $null -ne $create.Json -and $create.Json.order_id
            $records.Add([pscustomobject]@{
                service = 'orders'; target = $destination.Target; destination_service = $destination.DestinationService
                timestamp = $operationStarted.ToUniversalTime().ToString('o'); operation = 'create'
                method = 'POST'; uri = $ordersUri.AbsoluteUri; status_code = $create.StatusCode
                outcome = if ($createValid) { 'success' } else { 'failure' }
                error_type = if ($create.ErrorType) { $create.ErrorType } elseif (-not $createValid) { 'http-or-contract' } else { $null }
                latency_ms = $create.LatencyMs; idempotency_key = $idempotencyKey; operation_id = $null
                order_status = if ($create.Json) { [string]$create.Json.status } else { $null }
            })
            if (-not $createValid) {
                $failedTechnical++
                continue
            }

            $accepted++
            $orderId = [string]$create.Json.order_id
            $records[$records.Count - 1].operation_id = $orderId

            if ($effectiveMode -eq 'Rollout') {
                # Orders promotion owns successful local persistence/outbox
                # creation, not the downstream saga result. Do not replay the
                # idempotency key or wait for Inventory/Payments here.
                continue
            }

            if ((Get-Random -Minimum 0 -Maximum 100) -lt $DuplicatePercentage) {
                $duplicateAttempts++
                $duplicate = Invoke-EuroTransitHttpRequest -Uri $ordersUri -Method POST -Headers $headers -Body $body -TimeoutSeconds $settings.TimeoutSeconds
                $duplicateId = if ($duplicate.Json) { [string]$duplicate.Json.order_id } else { $null }
                $duplicateValid = $duplicate.StatusCode -in @(202, 409) -and $duplicateId -eq $orderId
                if (-not $duplicateValid) {
                    $idempotencyMismatches++
                    $failedTechnical++
                }
                $records.Add([pscustomobject]@{
                    service = 'orders'; target = $destination.Target; destination_service = $destination.DestinationService
                    timestamp = (Get-Date).ToUniversalTime().ToString('o'); operation = 'duplicate-create'
                    method = 'POST'; uri = $ordersUri.AbsoluteUri; status_code = $duplicate.StatusCode
                    outcome = if ($duplicateValid) { 'success' } else { 'failure' }
                    error_type = if ($duplicate.ErrorType) { $duplicate.ErrorType } elseif (-not $duplicateValid) { 'idempotency' } else { $null }
                    latency_ms = $duplicate.LatencyMs; idempotency_key = $idempotencyKey; operation_id = $duplicateId
                    order_status = if ($duplicate.Json) { [string]$duplicate.Json.status } else { $null }
                })
            }

            $pollDeadline = (Get-Date).AddSeconds($PollTimeoutSeconds)
            $terminalStatus = $null
            $observedStatuses = [System.Collections.Generic.List[string]]::new()
            while ((Get-Date) -lt $pollDeadline -and (Get-Date) -lt $timing.Deadline) {
                $pollUri = Join-EuroTransitUri -BaseUri $destination.BaseUri -Path "/api/v1/orders/$orderId"
                $poll = Invoke-EuroTransitHttpRequest -Uri $pollUri -Headers $headers -TimeoutSeconds $settings.TimeoutSeconds
                $pollValid = $poll.StatusCode -eq 200 -and $null -ne $poll.Json -and $poll.Json.order_id -eq $orderId
                $status = if ($pollValid) { [string]$poll.Json.status } else { $null }
                $records.Add([pscustomobject]@{
                    service = 'orders'; target = $destination.Target; destination_service = $destination.DestinationService
                    timestamp = (Get-Date).ToUniversalTime().ToString('o'); operation = 'poll'
                    method = 'GET'; uri = $pollUri.AbsoluteUri; status_code = $poll.StatusCode
                    outcome = if ($pollValid) { 'success' } else { 'failure' }
                    error_type = if ($poll.ErrorType) { $poll.ErrorType } elseif (-not $pollValid) { 'http-or-contract' } else { $null }
                    latency_ms = $poll.LatencyMs; idempotency_key = $idempotencyKey; operation_id = $orderId
                    order_status = $status
                })
                if (-not $pollValid) {
                    $failedTechnical++
                    break
                }
                if ($observedStatuses.Count -eq 0 -or $observedStatuses[$observedStatuses.Count - 1] -ne $status) {
                    $observedStatuses.Add($status)
                }
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

            $statusSequences.Add("${orderId}:$($observedStatuses -join '>')")
            $operationStopwatch.Stop()
            $endToEndLatencies.Add($operationStopwatch.Elapsed.TotalMilliseconds)
            switch ($terminalStatus) {
                'CONFIRMED' {
                    $confirmed++
                }
                'FAILED' {
                    $reachedReserved = $observedStatuses -contains 'RESERVED'
                    if (-not $reachedReserved) {
                        # Real Orders Stage1 writes FAILED directly when
                        # Inventory reports insufficient seats.
                        $failedInsufficientInventory++
                    }
                    elseif ($ExpectedOutcome -eq 'PaymentDeclined') {
                        # GET /orders/{id} contains no failure reason. A
                        # RESERVED -> FAILED sequence proves the failure
                        # occurred after Inventory, while the exact decline
                        # classification remains operator-declared.
                        $failedPaymentDeclined++
                    }
                    else {
                        $failedUnclassifiedBusiness++
                    }
                }
                default {
                    $pendingOrTimeout++
                }
            }
        }
        catch {
            $failedTechnical++
            $records.Add([pscustomobject]@{
                service = 'orders'; target = $destination.Target; destination_service = $destination.DestinationService
                timestamp = (Get-Date).ToUniversalTime().ToString('o'); operation = 'money-path'
                method = 'MULTI'; uri = $destination.BaseUri.AbsoluteUri; status_code = $null
                outcome = 'failure'; error_type = 'script'; latency_ms = $operationStopwatch.Elapsed.TotalMilliseconds
                idempotency_key = $null; operation_id = $null; order_status = $null
            })
        }
        finally {
            if ($operationStopwatch.IsRunning) { $operationStopwatch.Stop() }
            if ($accepted -lt $operationBudget) {
                Start-EuroTransitRateDelay -Deadline $timing.Deadline -BaseDelayMs $baseDelayMs -ElapsedMs $operationStopwatch.Elapsed.TotalMilliseconds
            }
        }
    }
}
finally {
    $requestVolumeSatisfied = $accepted -ge $minimumRequiredNewOperations
    $passed = if ($effectiveMode -eq 'Rollout') {
        $failedTechnical -eq 0 -and $requestVolumeSatisfied
    } else { switch ($ExpectedOutcome) {
        'Confirmed' {
            $confirmed -gt 0 -and $failedTechnical -eq 0 -and $pendingOrTimeout -eq 0 `
                -and $idempotencyMismatches -eq 0
        }
        'InsufficientInventory' {
            $failedInsufficientInventory -gt 0 -and $failedTechnical -eq 0 `
                -and $pendingOrTimeout -eq 0 -and $idempotencyMismatches -eq 0
        }
        'PaymentDeclined' {
            $failedPaymentDeclined -gt 0 -and $failedTechnical -eq 0 `
                -and $pendingOrTimeout -eq 0 -and $idempotencyMismatches -eq 0
        }
    } }
    $classificationSource = if ($ExpectedOutcome -eq 'Confirmed') {
        'order-status-only-no-failure-reason-in-api'
    }
    else {
        'operator-expected-scenario-plus-terminal-order-status'
    }
    $summary = New-EuroTransitTrafficSummary -Service orders -Destination $destination `
        -TrafficMode $(if ($effectiveMode -eq 'Rollout') { 'rollout-write' } else { 'money-path' }) `
        -Started $timing.Started -Finished (Get-Date) -Records $records `
        -Passed $passed -AdditionalFields @{
            catalog_target = $catalogDestination.Target
            catalog_destination_service = $catalogDestination.DestinationService
            catalog_lookups = $catalogLookups
            catalog_availability_authoritative = $false
            expected_outcome = $ExpectedOutcome
            failure_classification_source = $classificationSource
            new_operations = $accepted
            expected_new_operations = $expectedNewOperations
            minimum_required_new_operations = $minimumRequiredNewOperations
            request_volume_satisfied = $requestVolumeSatisfied
            confirmed = $confirmed
            confirmed_orders = $confirmed
            failed_insufficient_inventory = $failedInsufficientInventory
            failed_payment_declined = $failedPaymentDeclined
            failed_technical = $failedTechnical
            failed_unclassified_business = $failedUnclassifiedBusiness
            pending_or_timeout = $pendingOrTimeout
            pending_or_timed_out_orders = $pendingOrTimeout
            duplicate_attempts = $duplicateAttempts
            idempotency_mismatches = $idempotencyMismatches
            protocol_failures = $failedTechnical
            observed_status_sequences = @($statusSequences)
            average_end_to_end_ms = if ($endToEndLatencies.Count) { [Math]::Round(($endToEndLatencies | Measure-Object -Average).Average, 2) } else { 0 }
            p95_end_to_end_ms = Get-Percentile $endToEndLatencies 95
        }
    Write-EuroTransitResults -Records $records -Summary $summary -OutputDirectory $OutputDirectory -Started $timing.Started
    $summary
}
