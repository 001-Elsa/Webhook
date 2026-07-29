param(
    [string]$EvidenceDirectory = "docs/evidence",
    [int]$TimeoutSeconds = 90
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$evidence = Join-Path $root $EvidenceDirectory
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")

foreach ($name in "EVENTRELAY_APP_ID", "EVENTRELAY_API_KEY", "EVENTRELAY_ADMIN_APP_ID", "EVENTRELAY_ADMIN_API_KEY") {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) { throw "$name must be set" }
}

function New-Hex([int]$bytes) {
    -join ((1..$bytes | ForEach-Object { Get-Random -Minimum 0 -Maximum 256 | ForEach-Object { $_.ToString("x2") } }))
}

$traceId = New-Hex 16
$parentSpanId = New-Hex 8
$eventId = "trace-$stamp"
$eventType = "trace.evidence"
$producerHeaders = @{
    "Content-Type" = "application/json"; "X-App-Id" = $env:EVENTRELAY_APP_ID; "X-Api-Key" = $env:EVENTRELAY_API_KEY
    "X-Trace-Id" = $traceId; "traceparent" = "00-$traceId-$parentSpanId-01"
}
$adminHeaders = @{ "X-App-Id" = $env:EVENTRELAY_ADMIN_APP_ID; "X-Api-Key" = $env:EVENTRELAY_ADMIN_API_KEY }

function Invoke-Admin([string]$method, [string]$path, $body = $null) {
    $params = @{ Method = $method; Uri = "http://localhost:8080$path"; Headers = $adminHeaders; UseBasicParsing = $true }
    if ($null -ne $body) { $params.Body = ($body | ConvertTo-Json -Compress -Depth 8) }
    Invoke-RestMethod @params
}

# Use a dedicated matching endpoint so the trace validates a real webhook call rather than an empty event path.
$secret = (& docker compose exec -T receiver-mock sh -c 'printf %s "$RECEIVER_WEBHOOK_SECRET"').Trim()
if ([string]::IsNullOrWhiteSpace($secret)) { throw "Receiver mock has no webhook secret" }
$endpoint = @{ name = "trace-evidence-receiver"; url = "http://receiver-mock:8082/webhook/demo-merchant"; secret = $secret
    eventTypes = $eventType; active = $true; maxAttempts = 3; rateLimitPerMinute = 100000; filterExpression = $null }
$existing = Invoke-Admin "GET" "/api/endpoints" | Where-Object { $_.name -eq "trace-evidence-receiver" } | Select-Object -First 1
if ($null -eq $existing) { Invoke-Admin "POST" "/api/endpoints" $endpoint | Out-Null }
else { Invoke-Admin "PUT" "/api/endpoints/$($existing.id)" $endpoint | Out-Null }
Invoke-RestMethod -Method POST -Uri "http://localhost:8082/api/config" -ContentType "application/json" -Body '{"failNext":0,"delayMs":0}' | Out-Null

$accepted = Invoke-RestMethod -Method POST -Uri "http://localhost:8080/api/events" -Headers $producerHeaders -Body (@{ eventId = $eventId; type = $eventType; data = @{ purpose = "trace-evidence" } } | ConvertTo-Json -Compress)
if ($accepted.deliveryCount -lt 1) { throw "Trace evidence event created no Delivery" }

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
do {
    $state = Invoke-Admin "GET" "/api/events/$eventId/status"
    if ($state.status -in @("COMPLETED", "DEAD", "PARTIALLY_FAILED")) { break }
    Start-Sleep -Milliseconds 250
} while ((Get-Date) -lt $deadline)
if ($state.status -ne "COMPLETED") { throw "Trace event finished as $($state.status)" }
if ($state.traceId -ne $traceId) { throw "Persisted event trace ID does not match the supplied W3C trace ID" }

# Collector batching is configured for up to five seconds; wait for Jaeger to expose the joined trace.
$trace = $null
for ($i = 0; $i -lt 24; $i++) {
    try {
        $trace = Invoke-RestMethod -Uri "http://localhost:16686/api/traces/$traceId"
        if ($trace.data -and $trace.data.Count -gt 0) { break }
    } catch { }
    Start-Sleep -Seconds 2
}
if ($null -eq $trace -or -not $trace.data -or $trace.data.Count -eq 0) { throw "Jaeger did not return trace $traceId" }

$tracePath = Join-Path $evidence "trace-$stamp-$eventId.json"
$trace | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $tracePath -Encoding utf8
$spans = @($trace.data[0].spans)
$services = @($trace.data[0].processes.psobject.Properties | ForEach-Object { $_.Value.serviceName } | Sort-Object -Unique)
$expectedServices = @("eventrelay-api", "eventrelay-publisher", "eventrelay-worker", "eventrelay-receiver")
$missingServices = @($expectedServices | Where-Object { $_ -notin $services })
$rabbitSpans = @($spans | Where-Object {
    $_.tags | Where-Object { $_.key -eq "messaging.system" -and $_.value -match "rabbit" }
})
if ($missingServices.Count -gt 0) { throw "Trace is missing expected services: $($missingServices -join ', ')" }
if ($rabbitSpans.Count -lt 2) { throw "Trace does not contain both RabbitMQ producer and consumer spans" }
$reportPath = Join-Path $evidence "trace-$stamp-$eventId.md"
@"
# Distributed trace evidence ($stamp)

| Field | Value |
|---|---|
| Event ID | `$eventId` |
| Trace ID | `$traceId` |
| Event terminal state | `$($state.status)` |
| Span count | $($spans.Count) |
| Services observed | $($services -join ", ") |
| RabbitMQ spans | $($rabbitSpans.Count) |
| Jaeger UI | [open trace](http://localhost:16686/trace/$traceId) |
| Raw Jaeger response | `$tracePath` |

The script requires `eventrelay-api`, `eventrelay-publisher`, `eventrelay-worker`,
and `eventrelay-receiver`, plus both RabbitMQ producer and consumer spans. It
fails if Jaeger does not return the exact trace ID carried by the persisted Event,
so it cannot be satisfied by an unrelated trace.
"@ | Set-Content -LiteralPath $reportPath -Encoding utf8

Write-Host "Trace evidence report: $reportPath"
Write-Host "Jaeger trace: http://localhost:16686/trace/$traceId"
