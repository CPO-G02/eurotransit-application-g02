[CmdletBinding()]
param(
    [ValidateSet('Active', 'Preview')][string]$Target = 'Active',
    [string]$BaseUrl,
    [string]$PortForwardServiceName,
    [ValidateSet('Readiness', 'Business')][string]$Mode = 'Readiness',
    [string]$AccessToken,
    [switch]$AcknowledgeSeatConsumption,
    [string]$TrainId,
    [string]$SeatClass,
    [ValidateSet('Public', 'Stable', 'Canary', 'Active', 'Preview')]
    [string]$CatalogTarget = 'Public',
    [string]$CatalogBaseUrl,
    [string]$CatalogPortForwardServiceName,
    [ValidateRange(1, 20)][int]$Quantity = 1,
    [ValidateRange(1, 20)][int]$MaxNewOperations = 2,
    [ValidateRange(0, 100)][int]$DuplicatePercentage = 90,
    [int]$DurationMinutes = 20,
    [int]$DurationSeconds = 0,
    [ValidateRange(1, 6000)][int]$RequestsPerMinute = 60,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'results')
)

. (Join-Path $PSScriptRoot 'Common.ps1')

$destination = Resolve-EuroTransitDestination -Service inventory -Target $Target `
    -BaseUrl $BaseUrl -PortForwardServiceName $PortForwardServiceName

if ($Mode -eq 'Readiness') {
    Invoke-EuroTransitTraffic -Service inventory -Destination $destination `
        -Path '/actuator/health/readiness' -DurationMinutes $DurationMinutes `
        -DurationSeconds $DurationSeconds -RequestsPerMinute $RequestsPerMinute `
        -TrafficMode 'readiness-only' -ApplicationTraffic:$false `
        -OutputDirectory $OutputDirectory
    return
}

if (-not $AcknowledgeSeatConsumption) {
    throw 'Inventory Business mode permanently reserves seats. Pass -AcknowledgeSeatConsumption after choosing a safe low operation limit.'
}
if ($RequestsPerMinute -gt 10) {
    throw 'Inventory Business traffic is capped at 10 requests per minute.'
}
if ([bool]$TrainId -xor [bool]$SeatClass) {
    throw 'Specify both -TrainId and -SeatClass, or neither to select dynamically from Catalog.'
}
$token = Get-EuroTransitAccessToken $AccessToken
if (-not $token) {
    throw 'Inventory Business traffic requires -AccessToken or EUROTRANSIT_ACCESS_TOKEN.'
}
$headers = @{ Authorization = "Bearer $token" }
$catalogDestination = if (-not $TrainId) {
    Resolve-EuroTransitDestination -Service catalog -Target $CatalogTarget `
        -BaseUrl $CatalogBaseUrl -PortForwardServiceName $CatalogPortForwardServiceName
}
else {
    $null
}

$timing = Get-EuroTransitDeadline -DurationMinutes $DurationMinutes -DurationSeconds $DurationSeconds
$records = [System.Collections.Generic.List[object]]::new()
$successfulOperations = [System.Collections.Generic.List[object]]::new()
$baseDelayMs = 60000.0 / $RequestsPerMinute
$newAttempts = 0
$reservationsCreated = 0
$duplicateAttempts = 0
$idempotencyMismatches = 0
$insufficientSeats = 0
$protocolFailures = 0
$uri = Join-EuroTransitUri -BaseUri $destination.BaseUri -Path '/reserve'

try {
    while ((Get-Date) -lt $timing.Deadline) {
        $requestStarted = Get-Date
        $createNew = $successfulOperations.Count -eq 0 -or (
            $newAttempts -lt $MaxNewOperations -and
            (Get-Random -Minimum 0 -Maximum 100) -ge $DuplicatePercentage
        )

        if ($createNew -and $newAttempts -lt $MaxNewOperations) {
            $newAttempts++
            $selection = if ($TrainId) {
                [pscustomobject]@{ train_id = $TrainId; seat_class = $SeatClass; quantity = $Quantity }
            }
            else {
                Get-EuroTransitCatalogSelection -Destination $catalogDestination -Quantity $Quantity
            }
            $key = "demo-inventory-$([guid]::NewGuid())"
            $body = @{
                idempotency_key = $key
                train_id = $selection.train_id
                seat_class = $selection.seat_class
                quantity = $Quantity
            }
            $expectedReservationId = $null
            $operation = 'new-reservation'
        }
        elseif ($successfulOperations.Count -gt 0) {
            $duplicateAttempts++
            $existing = $successfulOperations | Get-Random
            $key = $existing.Key
            $body = $existing.Body
            $expectedReservationId = $existing.ReservationId
            $operation = 'duplicate-reservation'
        }
        else {
            break
        }

        $response = Invoke-EuroTransitHttpRequest -Uri $uri -Method POST -Headers $headers -Body $body
        $reservationId = if ($response.Json -and $response.Json.PSObject.Properties['reservation_id']) {
            [string]$response.Json.reservation_id
        }
        else {
            $null
        }
        $valid = $false
        $errorType = $response.ErrorType

        if ($operation -eq 'new-reservation' -and $response.StatusCode -eq 200 -and $reservationId) {
            $valid = $true
            $reservationsCreated++
            $successfulOperations.Add([pscustomobject]@{
                Key = $key
                Body = $body
                ReservationId = $reservationId
            })
        }
        elseif ($operation -eq 'new-reservation' -and $response.StatusCode -eq 409) {
            $valid = $true
            $insufficientSeats++
        }
        elseif ($operation -eq 'duplicate-reservation' -and $response.StatusCode -eq 200 -and $reservationId -eq $expectedReservationId) {
            $valid = $true
        }
        else {
            $protocolFailures++
            if ($operation -eq 'duplicate-reservation' -and $response.StatusCode -eq 200) {
                $idempotencyMismatches++
                $errorType = 'idempotency'
            }
            elseif (-not $errorType) {
                $errorType = 'http-or-contract'
            }
        }

        $records.Add([pscustomobject]@{
            service = 'inventory'; target = $destination.Target; destination_service = $destination.DestinationService
            timestamp = $requestStarted.ToUniversalTime().ToString('o'); operation = $operation
            method = 'POST'; uri = $uri.AbsoluteUri; status_code = $response.StatusCode
            outcome = if ($valid) { 'success' } else { 'failure' }; error_type = $errorType
            latency_ms = $response.LatencyMs; idempotency_key = $key; operation_id = $reservationId
        })
        Start-EuroTransitRateDelay -Deadline $timing.Deadline -BaseDelayMs $baseDelayMs -ElapsedMs $response.LatencyMs
    }
}
finally {
    $passed = $records.Count -gt 0 -and $reservationsCreated -gt 0 `
        -and $protocolFailures -eq 0 -and $idempotencyMismatches -eq 0
    $summary = New-EuroTransitTrafficSummary -Service inventory -Destination $destination `
        -TrafficMode 'idempotent-business' -Started $timing.Started -Finished (Get-Date) `
        -Records $records -Passed $passed -AdditionalFields @{
            catalog_target = if ($catalogDestination) { $catalogDestination.Target } else { $null }
            catalog_destination_service = if ($catalogDestination) { $catalogDestination.DestinationService } else { $null }
            new_operation_attempts = $newAttempts
            reservations_created = $reservationsCreated
            duplicate_attempts = $duplicateAttempts
            configured_duplicate_percentage = $DuplicatePercentage
            insufficient_seats = $insufficientSeats
            idempotency_mismatches = $idempotencyMismatches
            protocol_failures = $protocolFailures
        }
    Write-EuroTransitResults -Records $records -Summary $summary -OutputDirectory $OutputDirectory -Started $timing.Started
    $summary
}
