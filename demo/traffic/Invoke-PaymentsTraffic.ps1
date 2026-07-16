[CmdletBinding()]
param(
    [ValidateSet('Active', 'Preview')][string]$Target = 'Active',
    [string]$BaseUrl,
    [string]$PortForwardServiceName,
    [string]$KubernetesNamespace = 'eurotransit',
    [ValidateSet('Readiness', 'Business')][string]$Mode = 'Readiness',
    [string]$AccessToken,
    [switch]$AcknowledgeSafeGatewayConfiguration,
    [ValidateRange(0.01, 500)][decimal]$Amount = 1.00,
    [string]$Currency = 'EUR',
    [string]$UserId = 'demo-user',
    [ValidateRange(1, 20)][int]$MaxNewOperations = 3,
    [ValidateRange(0, 100)][int]$DuplicatePercentage = 90,
    [int]$DurationMinutes = 20,
    [int]$DurationSeconds = 0,
    [ValidateRange(1, 6000)][int]$RequestsPerMinute = 60,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'results')
)

. (Join-Path $PSScriptRoot 'Common.ps1')

$destination = Resolve-EuroTransitDestination -Service payments -Target $Target `
    -BaseUrl $BaseUrl -PortForwardServiceName $PortForwardServiceName `
    -KubernetesNamespace $KubernetesNamespace

if ($Mode -eq 'Readiness') {
    Invoke-EuroTransitTraffic -Service payments -Destination $destination `
        -Path '/actuator/health/readiness' -DurationMinutes $DurationMinutes `
        -DurationSeconds $DurationSeconds -RequestsPerMinute $RequestsPerMinute `
        -TrafficMode 'readiness-only' -ApplicationTraffic:$false `
        -OutputDirectory $OutputDirectory
    return
}

if (-not $AcknowledgeSafeGatewayConfiguration) {
    throw 'Payments Business mode invokes the configured payment gateway. Pass -AcknowledgeSafeGatewayConfiguration only after confirming it is a safe local/test gateway.'
}
if ($RequestsPerMinute -gt 10) {
    throw 'Payments Business traffic is capped at 10 requests per minute.'
}
$token = Get-EuroTransitAccessToken -Service payments -AccessToken $AccessToken
if (-not $token) {
    throw 'Payments Business traffic requires -AccessToken, EUROTRANSIT_PAYMENTS_ACCESS_TOKEN, or the explicit EUROTRANSIT_ACCESS_TOKEN compatibility fallback.'
}
$headers = @{ Authorization = "Bearer $token" }
$timing = Get-EuroTransitDeadline -DurationMinutes $DurationMinutes -DurationSeconds $DurationSeconds
$records = [System.Collections.Generic.List[object]]::new()
$successfulOperations = [System.Collections.Generic.List[object]]::new()
$baseDelayMs = 60000.0 / $RequestsPerMinute
$newAttempts = 0
$authorized = 0
$declined = 0
$duplicateAttempts = 0
$idempotencyMismatches = 0
$protocolFailures = 0
$uri = Join-EuroTransitUri -BaseUri $destination.BaseUri -Path '/api/v1/payments/authorize'

try {
    while ((Get-Date) -lt $timing.Deadline) {
        $requestStarted = Get-Date
        $createNew = $successfulOperations.Count -eq 0 -or (
            $newAttempts -lt $MaxNewOperations -and
            (Get-Random -Minimum 0 -Maximum 100) -ge $DuplicatePercentage
        )

        if ($createNew -and $newAttempts -lt $MaxNewOperations) {
            $newAttempts++
            $key = "demo-payment-$([guid]::NewGuid())"
            $body = @{
                idempotency_key = $key
                user_id = $UserId
                amount = $Amount
                currency = $Currency
            }
            $expectedTransactionId = $null
            $operation = 'new-authorization'
        }
        elseif ($successfulOperations.Count -gt 0) {
            $duplicateAttempts++
            $existing = $successfulOperations | Get-Random
            $key = $existing.Key
            $body = $existing.Body
            $expectedTransactionId = $existing.TransactionId
            $operation = 'duplicate-authorization'
        }
        else {
            break
        }

        $response = Invoke-EuroTransitHttpRequest -Uri $uri -Method POST -Headers $headers -Body $body
        $transactionId = if ($response.Json -and $response.Json.PSObject.Properties['transaction_id']) {
            [string]$response.Json.transaction_id
        }
        else {
            $null
        }
        $valid = $false
        $errorType = $response.ErrorType

        if ($operation -eq 'new-authorization' -and $response.StatusCode -eq 200 -and $transactionId) {
            $valid = $true
            $authorized++
            $successfulOperations.Add([pscustomobject]@{
                Key = $key
                Body = $body
                TransactionId = $transactionId
            })
        }
        elseif ($operation -eq 'new-authorization' -and $response.StatusCode -eq 402) {
            $valid = $true
            $declined++
        }
        elseif ($operation -eq 'duplicate-authorization' -and $response.StatusCode -eq 200 -and $transactionId -eq $expectedTransactionId) {
            $valid = $true
        }
        else {
            $protocolFailures++
            if ($operation -eq 'duplicate-authorization' -and $response.StatusCode -eq 200) {
                $idempotencyMismatches++
                $errorType = 'idempotency'
            }
            elseif (-not $errorType) {
                $errorType = 'http-or-contract'
            }
        }

        $records.Add([pscustomobject]@{
            service = 'payments'; target = $destination.Target; destination_service = $destination.DestinationService
            timestamp = $requestStarted.ToUniversalTime().ToString('o'); operation = $operation
            method = 'POST'; uri = $uri.AbsoluteUri; status_code = $response.StatusCode
            outcome = if ($valid) { 'success' } else { 'failure' }; error_type = $errorType
            latency_ms = $response.LatencyMs; idempotency_key = $key; operation_id = $transactionId
        })
        Start-EuroTransitRateDelay -Deadline $timing.Deadline -BaseDelayMs $baseDelayMs -ElapsedMs $response.LatencyMs
    }
}
finally {
    $passed = $records.Count -gt 0 -and $authorized -gt 0 `
        -and $protocolFailures -eq 0 -and $idempotencyMismatches -eq 0
    $summary = New-EuroTransitTrafficSummary -Service payments -Destination $destination `
        -TrafficMode 'idempotent-business' -Started $timing.Started -Finished (Get-Date) `
        -Records $records -Passed $passed -AdditionalFields @{
            new_operation_attempts = $newAttempts
            authorized_payments = $authorized
            declined_payments = $declined
            duplicate_attempts = $duplicateAttempts
            configured_duplicate_percentage = $DuplicatePercentage
            idempotency_mismatches = $idempotencyMismatches
            protocol_failures = $protocolFailures
        }
    Write-EuroTransitResults -Records $records -Summary $summary -OutputDirectory $OutputDirectory -Started $timing.Started
    $summary
}
