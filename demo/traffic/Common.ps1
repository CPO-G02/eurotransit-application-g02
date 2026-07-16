Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-EuroTransitAccessToken {
    param(
        [Parameter(Mandatory)]
        [ValidateSet('orders', 'inventory', 'payments')]
        [string]$Service,
        [string]$AccessToken
    )

    if ($AccessToken) { return $AccessToken }
    $serviceVariable = "EUROTRANSIT_$($Service.ToUpperInvariant())_ACCESS_TOKEN"
    $serviceToken = [Environment]::GetEnvironmentVariable($serviceVariable)
    if ($serviceToken) { return $serviceToken }
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

function Get-EuroTransitTrafficProfile {
    param(
        [Parameter(Mandatory)]
        [ValidateSet('Smoke', 'Rollout')]
        [string]$Profile
    )

    if ($Profile -eq 'Rollout') {
        return [pscustomobject]@{
            DurationMinutes = 25
            DurationSeconds = 0
            RequestsPerMinute = 120
            TimeoutSeconds = 5
            MaxConcurrency = 5
            MinimumRequestVolumePercentage = 90
        }
    }

    return [pscustomobject]@{
        DurationMinutes = 0
        DurationSeconds = 60
        RequestsPerMinute = 10
        TimeoutSeconds = 3
        MaxConcurrency = 1
        MinimumRequestVolumePercentage = 80
    }
}

function Resolve-EuroTransitTrafficSettings {
    param(
        [Parameter(Mandatory)]
        [ValidateSet('Smoke', 'Rollout')]
        [string]$Profile,
        [Parameter(Mandatory)][hashtable]$BoundParameters,
        [int]$DurationMinutes,
        [int]$DurationSeconds,
        [int]$RequestsPerMinute,
        [int]$TimeoutSeconds,
        [int]$MaxConcurrency,
        [int]$MinimumRequestVolumePercentage
    )

    $defaults = Get-EuroTransitTrafficProfile -Profile $Profile
    $durationWasExplicit = $BoundParameters.ContainsKey('DurationMinutes') -or
        $BoundParameters.ContainsKey('DurationSeconds')

    return [pscustomobject]@{
        DurationMinutes = if ($durationWasExplicit) { $DurationMinutes } else { $defaults.DurationMinutes }
        DurationSeconds = if ($durationWasExplicit) { $DurationSeconds } else { $defaults.DurationSeconds }
        RequestsPerMinute = if ($BoundParameters.ContainsKey('RequestsPerMinute')) { $RequestsPerMinute } else { $defaults.RequestsPerMinute }
        TimeoutSeconds = if ($BoundParameters.ContainsKey('TimeoutSeconds')) { $TimeoutSeconds } else { $defaults.TimeoutSeconds }
        MaxConcurrency = if ($BoundParameters.ContainsKey('MaxConcurrency')) { $MaxConcurrency } else { $defaults.MaxConcurrency }
        MinimumRequestVolumePercentage = if ($BoundParameters.ContainsKey('MinimumRequestVolumePercentage')) {
            $MinimumRequestVolumePercentage
        } else {
            $defaults.MinimumRequestVolumePercentage
        }
    }
}

function Get-EuroTransitDisplayName {
    param([Parameter(Mandatory)][string]$Service)

    switch ($Service) {
        'payment-gateway-sim' { 'Payment Gateway Sim' }
        default {
            (Get-Culture).TextInfo.ToTitleCase($Service.Replace('-', ' '))
        }
    }
}

function Write-EuroTransitRequestResult {
    param([Parameter(Mandatory)][psobject]$Record)

    $displayName = Get-EuroTransitDisplayName -Service $Record.service
    $number = ([int]$Record.request_number).ToString('000')
    $latency = [Math]::Round([double]$Record.latency_ms)
    if ($Record.outcome -eq 'success') {
        Write-Host "Request $number on ${displayName}: OK ($($Record.status_code), $latency ms)"
        return
    }

    $reason = if ($Record.error_type -eq 'timeout') {
        'TIMEOUT'
    } elseif ($null -ne $Record.status_code) {
        "HTTP $($Record.status_code)"
    } elseif ($Record.error_type) {
        ([string]$Record.error_type).ToUpperInvariant()
    } else {
        'UNKNOWN'
    }
    Write-Host "Request $number on ${displayName}: FAIL ($reason, $latency ms)"
}

function Write-EuroTransitCompactSummary {
    param([Parameter(Mandatory)][psobject]$Summary)

    $displayName = Get-EuroTransitDisplayName -Service $Summary.service
    $result = if ($Summary.passed) { 'PASS' } else { 'FAIL' }
    $p95 = [Math]::Round([double]$Summary.p95_latency_ms)
    Write-Host "$displayName`: $result | Requests: $($Summary.total_requests) | OK: $($Summary.successes) | FAIL: $($Summary.failures) | Timeouts: $($Summary.timeouts) | P95: $p95 ms"
}

function Add-EuroTransitTypeName {
    param(
        [Parameter(Mandatory)][psobject]$InputObject,
        [Parameter(Mandatory)][string]$TypeName
    )

    $InputObject.PSObject.TypeNames.Insert(0, $TypeName)
    return $InputObject
}

function Resolve-EuroTransitDestination {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Service,
        [Parameter(Mandatory)]
        [ValidateSet('Public', 'Stable', 'Canary', 'Active', 'Preview')]
        [string]$Target,
        [string]$BaseUrl,
        [string]$PortForwardServiceName,
        [string]$PublicBaseUrl = 'https://g02.cpo2026.it',
        [string]$ExpectedPublicHost = 'g02.cpo2026.it',
        [ValidateSet('http', 'https')][string]$ExpectedPublicScheme = 'https',
        [ValidateRange(1, 65535)][int]$ExpectedPublicPort = 443,
        [ValidatePattern('^[a-z0-9]([-a-z0-9]*[a-z0-9])?$')]
        [string]$KubernetesNamespace = 'eurotransit'
    )

    $baseService = "eurotransit-$Service"
    $expectedService = switch ($Target) {
        'Canary' { "$baseService-canary" }
        'Preview' { "$baseService-preview" }
        default { $baseService }
    }

    if ($Target -eq 'Public') {
        if ($PortForwardServiceName) {
            throw 'Target Public cannot use -PortForwardServiceName.'
        }
        $rawPublicUrl = if ($BaseUrl) { $BaseUrl } else { $PublicBaseUrl }
        try { $uri = [uri]$rawPublicUrl } catch { throw "Invalid public BaseUrl '$rawPublicUrl'." }
        if (-not $uri.IsAbsoluteUri) { throw "Public BaseUrl '$rawPublicUrl' must be absolute." }
        if ($uri.Host -in @('localhost', '127.0.0.1', '::1')) {
            throw 'Target Public must resolve through the public ingress, not a loopback port-forward.'
        }
        $normalizedExpectedHost = $ExpectedPublicHost.TrimEnd('.').ToLowerInvariant()
        $normalizedActualHost = $uri.DnsSafeHost.TrimEnd('.').ToLowerInvariant()
        if ($normalizedActualHost -ne $normalizedExpectedHost) {
            throw "Target Public expects host '$normalizedExpectedHost', got '$normalizedActualHost'. Override -ExpectedPublicHost explicitly for another environment."
        }
        if ($uri.Scheme -ne $ExpectedPublicScheme) {
            throw "Target Public expects scheme '$ExpectedPublicScheme', got '$($uri.Scheme)'."
        }
        if ($uri.Port -ne $ExpectedPublicPort) {
            throw "Target Public expects port '$ExpectedPublicPort', got '$($uri.Port)'."
        }
        $officialIngress = $normalizedExpectedHost -eq 'g02.cpo2026.it' `
            -and $ExpectedPublicScheme -eq 'https' -and $ExpectedPublicPort -eq 443
        return [pscustomobject]@{
            Service = $Service
            Target = $Target
            BaseUri = $uri
            DestinationService = if ($officialIngress) { 'traefik-public-route' } else { 'configured-public-route' }
            Validation = if ($officialIngress) { 'official-public-ingress' } else { 'explicit-public-environment' }
            ApplicationTraffic = $true
        }
    }

    if (-not $BaseUrl) {
        throw "Target $Target for $Service requires an explicit -BaseUrl."
    }

    try { $uri = [uri]$BaseUrl } catch { throw "Invalid BaseUrl '$BaseUrl'." }
    if (-not $uri.IsAbsoluteUri) { throw "BaseUrl '$BaseUrl' must be absolute." }

    $loopback = $uri.Host -in @('localhost', '127.0.0.1', '::1')
    if ($loopback) {
        if (-not $PortForwardServiceName) {
            throw "Loopback BaseUrl '$BaseUrl' requires -PortForwardServiceName $expectedService."
        }
        if ($PortForwardServiceName -ne $expectedService) {
            throw "Target $Target for $Service expects port-forward service '$expectedService', got '$PortForwardServiceName'."
        }
        $validation = 'asserted-port-forward'
    }
    else {
        $normalizedHost = $uri.DnsSafeHost.TrimEnd('.').ToLowerInvariant()
        $normalizedNamespace = $KubernetesNamespace.ToLowerInvariant()
        $allowedHosts = @(
            $expectedService,
            "$expectedService.$normalizedNamespace",
            "$expectedService.$normalizedNamespace.svc",
            "$expectedService.$normalizedNamespace.svc.cluster.local"
        )
        if ($normalizedHost -notin $allowedHosts) {
            throw "Target $Target for $Service expects Kubernetes Service DNS '$expectedService' in namespace '$normalizedNamespace', got '$normalizedHost'."
        }
        if ($PortForwardServiceName -and $PortForwardServiceName -ne $expectedService) {
            throw "PortForwardServiceName '$PortForwardServiceName' does not match '$expectedService'."
        }
        $validation = 'service-dns'
    }

    return [pscustomobject]@{
        Service = $Service
        Target = $Target
        BaseUri = $uri
        DestinationService = $expectedService
        Validation = $validation
        ApplicationTraffic = $true
    }
}

function Join-EuroTransitUri {
    param(
        [Parameter(Mandatory)][uri]$BaseUri,
        [Parameter(Mandatory)][string]$Path
    )

    return [uri]::new($BaseUri, $Path)
}

function Invoke-EuroTransitHttpRequest {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][uri]$Uri,
        [ValidateSet('GET', 'POST')][string]$Method = 'GET',
        [hashtable]$Headers = @{},
        [AllowNull()][object]$Body,
        [ValidateRange(1, 300)][int]$TimeoutSeconds = 10
    )

    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $parameters = @{
            Uri = $Uri
            Method = $Method
            Headers = $Headers
            TimeoutSec = $TimeoutSeconds
            UseBasicParsing = $true
            SkipHttpErrorCheck = $true
        }
        if ($null -ne $Body) {
            $parameters.ContentType = 'application/json'
            $parameters.Body = $Body | ConvertTo-Json -Depth 12 -Compress
        }

        $response = Invoke-WebRequest @parameters
        $json = $null
        if ($response.Content) {
            try { $json = $response.Content | ConvertFrom-Json -Depth 20 } catch { $json = $null }
        }

        return [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            Content = [string]$response.Content
            Json = $json
            ErrorType = $null
            ErrorMessage = $null
            LatencyMs = [Math]::Round($stopwatch.Elapsed.TotalMilliseconds, 2)
        }
    }
    catch {
        $errorType = if ($_.Exception.Message -match 'timed out|timeout|operation was canceled') {
            'timeout'
        }
        else {
            'network'
        }
        return [pscustomobject]@{
            StatusCode = $null
            Content = $null
            Json = $null
            ErrorType = $errorType
            ErrorMessage = $_.Exception.Message
            LatencyMs = [Math]::Round($stopwatch.Elapsed.TotalMilliseconds, 2)
        }
    }
    finally {
        $stopwatch.Stop()
    }
}

function Get-EuroTransitDeadline {
    param(
        [int]$DurationMinutes,
        [int]$DurationSeconds
    )

    if ($DurationMinutes -lt 1 -and $DurationSeconds -lt 1) {
        throw 'A positive duration is required.'
    }
    $started = Get-Date
    $deadline = if ($DurationSeconds -gt 0) {
        $started.AddSeconds($DurationSeconds)
    }
    else {
        $started.AddMinutes($DurationMinutes)
    }
    return [pscustomobject]@{ Started = $started; Deadline = $deadline }
}

function Start-EuroTransitRateDelay {
    param(
        [Parameter(Mandatory)][datetime]$Deadline,
        [Parameter(Mandatory)][double]$BaseDelayMs,
        [Parameter(Mandatory)][double]$ElapsedMs
    )

    $remainingMs = ($Deadline - (Get-Date)).TotalMilliseconds
    if ($remainingMs -le 0) { return }
    $jitter = (Get-Random -Minimum 90 -Maximum 111) / 100.0
    $sleepMs = [Math]::Min($remainingMs, [Math]::Max(0, ($BaseDelayMs * $jitter) - $ElapsedMs))
    if ($sleepMs -gt 0) { Start-Sleep -Milliseconds ([int]$sleepMs) }
}

function Write-EuroTransitResults {
    param(
        [Parameter(Mandatory)][System.Collections.IEnumerable]$Records,
        [Parameter(Mandatory)][psobject]$Summary,
        [Parameter(Mandatory)][string]$OutputDirectory,
        [Parameter(Mandatory)][datetime]$Started
    )

    $normalizedRecords = @($Records)
    for ($index = 0; $index -lt $normalizedRecords.Count; $index++) {
        $record = $normalizedRecords[$index]
        $wasMissingRequestNumber = $record.PSObject.Properties.Name -notcontains 'request_number'
        if ($wasMissingRequestNumber) {
            $record | Add-Member -NotePropertyName request_number -NotePropertyValue ($index + 1)
        }
        if ($record.PSObject.Properties.Name -notcontains 'error_message') {
            $record | Add-Member -NotePropertyName error_message -NotePropertyValue $null
        }
        if ($wasMissingRequestNumber) {
            Write-EuroTransitRequestResult -Record $record
        }
    }

    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
    $stamp = $Started.ToUniversalTime().ToString('yyyyMMddTHHmmssfffZ')
    $prefix = Join-Path $OutputDirectory "$($Summary.service)-$($Summary.target)-$stamp"
    $normalizedRecords | Export-Csv -NoTypeInformation -Encoding UTF8 -Path "$prefix.csv"
    $Summary | ConvertTo-Json -Depth 12 | Set-Content -Encoding UTF8 -Path "$prefix-summary.json"
    Write-EuroTransitCompactSummary -Summary $Summary
}

function New-EuroTransitTrafficSummary {
    param(
        [Parameter(Mandatory)][string]$Service,
        [Parameter(Mandatory)][psobject]$Destination,
        [Parameter(Mandatory)][string]$TrafficMode,
        [Parameter(Mandatory)][datetime]$Started,
        [Parameter(Mandatory)][datetime]$Finished,
        [Parameter(Mandatory)][System.Collections.IEnumerable]$Records,
        [Parameter(Mandatory)][bool]$Passed,
        [hashtable]$AdditionalFields = @{}
    )

    $allRecords = @($Records)
    $latencies = @($allRecords | ForEach-Object { [double]$_.latency_ms })
    $failures = @($allRecords | Where-Object outcome -eq 'failure').Count
    $statusCounts = @{}
    foreach ($group in ($allRecords | Where-Object { $null -ne $_.status_code } | Group-Object status_code)) {
        $statusCounts[$group.Name] = $group.Count
    }

    $properties = [ordered]@{
        service = $Service
        target = $Destination.Target
        destination_service = $Destination.DestinationService
        base_uri = $Destination.BaseUri.AbsoluteUri
        destination_validation = $Destination.Validation
        traffic_mode = $TrafficMode
        application_traffic = $Destination.ApplicationTraffic
        start = $Started.ToUniversalTime().ToString('o')
        end = $Finished.ToUniversalTime().ToString('o')
        total_requests = $allRecords.Count
        successes = $allRecords.Count - $failures
        failures = $failures
        failure_rate = if ($allRecords.Count) { [Math]::Round($failures / $allRecords.Count, 4) } else { 1 }
        status_codes = $statusCounts
        average_latency_ms = if ($latencies.Count) { [Math]::Round(($latencies | Measure-Object -Average).Average, 2) } else { 0 }
        p50_latency_ms = Get-Percentile $latencies 50
        p95_latency_ms = Get-Percentile $latencies 95
        maximum_latency_ms = if ($latencies.Count) { [Math]::Round(($latencies | Measure-Object -Maximum).Maximum, 2) } else { 0 }
        timeouts = @($allRecords | Where-Object error_type -eq 'timeout').Count
        passed = $Passed
    }
    foreach ($entry in $AdditionalFields.GetEnumerator()) {
        $properties[$entry.Key] = $entry.Value
    }

    return Add-EuroTransitTypeName -InputObject ([pscustomobject]$properties) -TypeName 'EuroTransit.TrafficSummary'
}

function Invoke-EuroTransitTraffic {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Service,
        [Parameter(Mandatory)][psobject]$Destination,
        [Parameter(Mandatory)][string]$Path,
        [ValidateSet('GET', 'POST')][string]$Method = 'GET',
        [int]$DurationMinutes = 20,
        [int]$DurationSeconds = 0,
        [ValidateRange(1, 6000)][int]$RequestsPerMinute = 60,
        [ValidateRange(1, 300)][int]$TimeoutSeconds = 10,
        [ValidateRange(1, 50)][int]$MaxConcurrency = 1,
        [ValidateRange(1, 100)][int]$MinimumRequestVolumePercentage = 80,
        [hashtable]$Headers = @{},
        [scriptblock]$BodyFactory,
        [int[]]$ExpectedStatusCodes = @(200),
        [string]$TrafficMode = 'read-only',
        [bool]$ApplicationTraffic = $true,
        [string]$OutputDirectory = (Join-Path $PSScriptRoot 'results'),
        [double]$MaximumFailureRate = 0.01
    )

    $timing = Get-EuroTransitDeadline -DurationMinutes $DurationMinutes -DurationSeconds $DurationSeconds
    $records = [System.Collections.Generic.List[object]]::new()
    $inFlight = [System.Collections.Generic.List[object]]::new()
    $baseDelayMs = 60000.0 / $RequestsPerMinute
    $uri = Join-EuroTransitUri -BaseUri $Destination.BaseUri -Path $Path
    $plannedDurationSeconds = ($timing.Deadline - $timing.Started).TotalSeconds
    $expectedRequests = [Math]::Max(1, [Math]::Floor($plannedDurationSeconds * $RequestsPerMinute / 60.0))
    $minimumRequiredRequests = [Math]::Ceiling($expectedRequests * $MinimumRequestVolumePercentage / 100.0)
    $nextScheduled = $timing.Started
    $requestNumber = 0
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [System.Threading.Timeout]::InfiniteTimeSpan

    $completeRequests = {
        param([switch]$WaitForOne)

        if ($inFlight.Count -eq 0) { return }
        if ($WaitForOne) {
            $tasks = [System.Threading.Tasks.Task[]]@($inFlight | ForEach-Object Task)
            [void][System.Threading.Tasks.Task]::WaitAny($tasks, 100)
        }

        foreach ($pending in @($inFlight | Where-Object { $_.Task.IsCompleted })) {
            $statusCode = $null
            $errorType = $null
            $errorMessage = $null
            $response = $null
            try {
                $response = $pending.Task.GetAwaiter().GetResult()
                $statusCode = [int]$response.StatusCode
            }
            catch [System.Threading.Tasks.TaskCanceledException] {
                $errorType = 'timeout'
                $errorMessage = "Request exceeded the configured timeout of $TimeoutSeconds seconds."
            }
            catch {
                $errorType = 'network'
                $errorMessage = $_.Exception.Message
            }
            finally {
                $pending.Stopwatch.Stop()
            }

            $expected = $null -ne $statusCode -and $ExpectedStatusCodes -contains $statusCode
            $record = [pscustomobject]@{
                request_number = $pending.RequestNumber
                timestamp = $pending.Started.ToUniversalTime().ToString('o')
                service = $Service
                target = $Destination.Target
                destination_service = $Destination.DestinationService
                method = $Method
                uri = $uri.AbsoluteUri
                status_code = $statusCode
                outcome = if ($expected) { 'success' } else { 'failure' }
                error_type = if ($errorType) { $errorType } elseif (-not $expected) { 'http' } else { $null }
                error_message = $errorMessage
                latency_ms = [Math]::Round($pending.Stopwatch.Elapsed.TotalMilliseconds, 2)
            }
            $records.Add($record)
            Write-EuroTransitRequestResult -Record $record

            if ($null -ne $response) { $response.Dispose() }
            $pending.Request.Dispose()
            $pending.Cancellation.Dispose()
            [void]$inFlight.Remove($pending)
        }
    }

    try {
        while ((Get-Date) -lt $timing.Deadline -or $inFlight.Count -gt 0) {
            & $completeRequests
            $now = Get-Date
            if ($now -lt $timing.Deadline -and $now -ge $nextScheduled -and $inFlight.Count -lt $MaxConcurrency) {
                $requestNumber++
                $requestStarted = Get-Date
                $request = [System.Net.Http.HttpRequestMessage]::new(
                    [System.Net.Http.HttpMethod]::new($Method),
                    $uri
                )
                foreach ($header in $Headers.GetEnumerator()) {
                    [void]$request.Headers.TryAddWithoutValidation([string]$header.Key, [string]$header.Value)
                }
                $body = if ($BodyFactory) { & $BodyFactory ($requestNumber - 1) } else { $null }
                if ($null -ne $body) {
                    $json = $body | ConvertTo-Json -Depth 12 -Compress
                    $request.Content = [System.Net.Http.StringContent]::new(
                        $json,
                        [System.Text.Encoding]::UTF8,
                        'application/json'
                    )
                }
                $cancellation = [System.Threading.CancellationTokenSource]::new()
                $cancellation.CancelAfter([TimeSpan]::FromSeconds($TimeoutSeconds))
                $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
                $task = $client.SendAsync(
                    $request,
                    [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead,
                    $cancellation.Token
                )
                $inFlight.Add([pscustomobject]@{
                    RequestNumber = $requestNumber
                    Started = $requestStarted
                    Request = $request
                    Cancellation = $cancellation
                    Stopwatch = $stopwatch
                    Task = $task
                })
                $nextScheduled = $timing.Started.AddMilliseconds($requestNumber * $baseDelayMs)
                continue
            }

            if ($inFlight.Count -gt 0) {
                & $completeRequests -WaitForOne
            } else {
                $sleepMs = [Math]::Min(100, [Math]::Max(1, ($nextScheduled - (Get-Date)).TotalMilliseconds))
                Start-Sleep -Milliseconds ([int]$sleepMs)
            }
        }
    }
    finally {
        while ($inFlight.Count -gt 0) {
            & $completeRequests -WaitForOne
        }
        $client.Dispose()
        $finished = Get-Date
        $failures = @($records | Where-Object outcome -eq 'failure').Count
        $failureRate = if ($records.Count) { $failures / $records.Count } else { 1 }
        $requestVolumeSatisfied = $records.Count -ge $minimumRequiredRequests
        $summary = New-EuroTransitTrafficSummary -Service $Service -Destination $Destination -TrafficMode $TrafficMode `
            -Started $timing.Started -Finished $finished -Records $records `
            -Passed ($records.Count -gt 0 -and $failureRate -le $MaximumFailureRate -and $requestVolumeSatisfied) `
            -AdditionalFields @{
                application_traffic = $ApplicationTraffic
                expected_requests = $expectedRequests
                minimum_required_requests = $minimumRequiredRequests
                actual_requests = $records.Count
                request_volume_satisfied = $requestVolumeSatisfied
                timeout_seconds = $TimeoutSeconds
                max_concurrency = $MaxConcurrency
                minimum_request_volume_percentage = $MinimumRequestVolumePercentage
            }
        Write-EuroTransitResults -Records $records -Summary $summary -OutputDirectory $OutputDirectory -Started $timing.Started
        $summary
    }
}

function Stop-EuroTransitJobs {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [System.Collections.IEnumerable]$Jobs
    )

    foreach ($job in @($Jobs)) {
        if ($null -eq $job) { continue }
        $job | Stop-Job -ErrorAction SilentlyContinue
        $job | Remove-Job -Force -ErrorAction SilentlyContinue
    }
}

function Get-EuroTransitCatalogSelection {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][psobject]$Destination,
        [ValidateRange(1, 20)][int]$Quantity = 1,
        [ValidateRange(1, 100000)][int]$MinimumAvailability = 1,
        [string]$TrainId,
        [Alias('SeatClass')][string]$RequestedSeatClass,
        [int]$TimeoutSeconds = 10
    )

    if ([bool]$TrainId -xor [bool]$RequestedSeatClass) {
        throw 'Specify both TrainId and SeatClass, or neither.'
    }
    $uri = Join-EuroTransitUri -BaseUri $Destination.BaseUri -Path '/api/v1/catalog/products'
    $response = Invoke-EuroTransitHttpRequest -Uri $uri -TimeoutSeconds $TimeoutSeconds
    if ($response.StatusCode -ne 200 -or $null -eq $response.Json) {
        throw "Catalog selection failed with status '$($response.StatusCode)' and error '$($response.ErrorType)'."
    }

    $candidates = [System.Collections.Generic.List[object]]::new()
    foreach ($product in @($response.Json.products)) {
        if ($TrainId -and [string]$product.train_id -ne $TrainId) { continue }
        foreach ($seatClassOffer in @($product.seat_classes)) {
            if ($RequestedSeatClass -and [string]$seatClassOffer.class -ne $RequestedSeatClass) { continue }
            $availabilityAccepted = if ($TrainId) {
                # Catalog is stale-tolerant. Explicit demo data is selected even
                # when its nominal availability is zero; Inventory is authoritative.
                $true
            }
            else {
                [int]$seatClassOffer.available -ge [Math]::Max($Quantity, $MinimumAvailability)
            }
            if ($availabilityAccepted) {
                $candidates.Add([pscustomobject]@{
                    train_id = [string]$product.train_id
                    seat_class = [string]$seatClassOffer.class
                    quantity = $Quantity
                    unit_price = [decimal]$seatClassOffer.price
                    amount = [decimal]$seatClassOffer.price * $Quantity
                    currency = [string]$seatClassOffer.currency
                    catalog_available = [int]$seatClassOffer.available
                })
            }
        }
    }
    if ($candidates.Count -eq 0) {
        if ($TrainId) {
            throw "Catalog returned no exact offer for train '$TrainId' and class '$RequestedSeatClass'."
        }
        throw "Catalog returned no seat class with at least $([Math]::Max($Quantity, $MinimumAvailability)) available seats."
    }
    return $candidates | Get-Random
}
