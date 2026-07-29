param(
    [string]$K6 = "k6",
    [int[]]$Rates = @(50, 100, 200),
    [int]$Repetitions = 3,
    [string]$Duration = "60s",
    [string]$OutputDirectory = "data/performance",
    [string]$EvidenceDirectory = "docs/evidence"
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$output = Join-Path $root $OutputDirectory
$evidence = Join-Path $root $EvidenceDirectory
New-Item -ItemType Directory -Force -Path $output, $evidence | Out-Null
$stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
$tableFragment = Join-Path $evidence "performance-$stamp.md"
$eventType = "performance.e2e"

foreach ($name in "EVENTRELAY_APP_ID", "EVENTRELAY_API_KEY", "EVENTRELAY_ADMIN_APP_ID", "EVENTRELAY_ADMIN_API_KEY") {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        throw "$name must be set before running the performance suite"
    }
}
& docker compose ps | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Docker Compose must be running before this suite starts" }
& $K6 version | Out-Null
if ($LASTEXITCODE -ne 0) { throw "k6 was not found; pass -K6 with its executable path" }

$producerHeaders = @{
    "Content-Type" = "application/json"
    "X-App-Id" = $env:EVENTRELAY_APP_ID
    "X-Api-Key" = $env:EVENTRELAY_API_KEY
}
$adminHeaders = @{
    "Content-Type" = "application/json"
    "X-App-Id" = $env:EVENTRELAY_ADMIN_APP_ID
    "X-Api-Key" = $env:EVENTRELAY_ADMIN_API_KEY
}

function Invoke-Api([string]$method, [string]$path, [hashtable]$headers, $body = $null) {
    $params = @{ Method = $method; Uri = "http://localhost:8080$path"; Headers = $headers; UseBasicParsing = $true }
    if ($null -ne $body) { $params.Body = ($body | ConvertTo-Json -Compress -Depth 8) }
    return Invoke-RestMethod @params
}

function Wait-EventTerminal([string]$eventId, [int]$timeoutSeconds = 90) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    do {
        $state = Invoke-Api "GET" "/api/events/$eventId/status" $adminHeaders
        if ($state.status -in @("COMPLETED", "DEAD", "PARTIALLY_FAILED")) { return $state }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    throw "Event $eventId did not reach a terminal state within $timeoutSeconds seconds"
}

function Ensure-PerformanceEndpoint {
    $secret = (& docker compose exec -T receiver-mock sh -c 'printf %s "$RECEIVER_WEBHOOK_SECRET"').Trim()
    if ([string]::IsNullOrWhiteSpace($secret)) { throw "Receiver mock has no webhook secret" }
    $definition = @{
        name = "performance-receiver"
        url = "http://receiver-mock:8082/webhook/demo-merchant"
        secret = $secret
        eventTypes = $eventType
        active = $true
        maxAttempts = 3
        rateLimitPerMinute = 100000
        filterExpression = $null
    }
    $existing = (Invoke-Api "GET" "/api/endpoints" $adminHeaders | Where-Object { $_.name -eq "performance-receiver" } | Select-Object -First 1)
    if ($null -eq $existing) {
        Invoke-Api "POST" "/api/endpoints" $adminHeaders $definition | Out-Null
    } else {
        Invoke-Api "PUT" "/api/endpoints/$($existing.id)" $adminHeaders $definition | Out-Null
    }
    Invoke-RestMethod -Method POST -Uri "http://localhost:8082/api/config" -ContentType "application/json" -Body '{"failNext":0,"delayMs":0}' | Out-Null

    $preflightId = "perf-preflight-$stamp"
    $accepted = Invoke-Api "POST" "/api/events" $producerHeaders @{
        eventId = $preflightId; type = $eventType; data = @{ source = "performance-preflight" }
    }
    if ($accepted.deliveryCount -lt 1) {
        throw "Performance preflight did not create a matching Delivery; refusing to test an empty endpoint chain"
    }
    $terminal = Wait-EventTerminal $preflightId
    if ($terminal.status -ne "COMPLETED") { throw "Performance preflight ended as $($terminal.status), not COMPLETED" }
}

function Snapshot([string]$name) {
    & docker compose exec -T eventrelay-api curl -fsS http://localhost:8083/actuator/prometheus |
        Set-Content -LiteralPath (Join-Path $output "$name.prom") -Encoding utf8
    & docker stats --no-stream --format "{{json .}}" |
        Set-Content -LiteralPath (Join-Path $output "$name-docker-stats.jsonl") -Encoding utf8
    & docker compose exec -T mysql mysql -u$env:EVENTRELAY_MYSQL_USER -p$env:EVENTRELAY_MYSQL_PASSWORD event_relay -e "SHOW GLOBAL STATUS LIKE 'Threads_waiting'; SELECT * FROM performance_schema.data_lock_waits;" |
        Set-Content -LiteralPath (Join-Path $output "$name-mysql.txt") -Encoding utf8
    & docker compose exec -T rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers |
        Set-Content -LiteralPath (Join-Path $output "$name-rabbitmq.txt") -Encoding utf8
}

function Read-Gauge([string]$service, [string]$metric) {
    $text = & docker compose exec -T $service curl -fsS http://localhost:8083/actuator/prometheus
    $line = ($text | Select-String -Pattern "^$metric(?:\{|\s)").Line | Select-Object -First 1
    if (-not $line) { throw "Metric $metric was not exposed by $service" }
    return [double](($line -split '\s+')[-1])
}

function Wait-Drained([string]$service, [string]$metric) {
    $started = Get-Date
    $maximum = 0.0
    for ($i = 0; $i -lt 600; $i++) {
        $value = Read-Gauge $service $metric
        $maximum = [Math]::Max($maximum, $value)
        if ($value -le 0) {
            return [pscustomobject]@{ maxBacklog = $maximum; drainSeconds = [Math]::Round(((Get-Date) - $started).TotalSeconds, 2) }
        }
        Start-Sleep -Milliseconds 500
    }
    throw "$metric did not drain"
}

function Read-K6Summary([string]$summaryPath, [string]$chain) {
    $metrics = (Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json).metrics
    $latency = if ($chain -eq "e2e") { $metrics.eventrelay_e2e_latency } else { $metrics.eventrelay_submit_latency }
    $submit = $metrics.eventrelay_event_submits
    $failure = if ($chain -eq "e2e") { $metrics.eventrelay_terminal_failure } else { $metrics.eventrelay_submit_failure }
    return [pscustomobject]@{
        tps = if ($submit -and $submit.rate) { [Math]::Round($submit.rate, 2) } else { "" }
        p95 = if ($latency -and $latency.'p(95)') { [Math]::Round($latency.'p(95)', 2) } else { "" }
        p99 = if ($latency -and $latency.'p(99)') { [Math]::Round($latency.'p(99)', 2) } else { "" }
        errorPct = if ($failure -and $null -ne $failure.value) { [Math]::Round(100.0 * $failure.value, 3) } else { "" }
    }
}

function Prometheus-Max([string]$metric, [string]$window) {
    try {
        $query = [uri]::EscapeDataString("max_over_time($metric[$window])")
        $result = Invoke-RestMethod -Uri "http://localhost:9090/api/v1/query?query=$query"
        if ($result.status -eq "success" -and $result.data.result.Count -gt 0) {
            return [double]$result.data.result[0].value[1]
        }
    } catch { }
    return $null
}

function Add-Result([string]$chain, [int]$rate, [int]$round, $summary, $outbox, $delivery) {
    [pscustomobject]@{
        chain = $chain; rate = $rate; round = $round; tps = $summary.tps; p95Ms = $summary.p95
        p99Ms = $summary.p99; errorPct = $summary.errorPct; outboxMaxBacklog = $outbox.maxBacklog
        outboxDrainSeconds = $outbox.drainSeconds; deliveryMaxBacklog = $delivery.maxBacklog; deliveryDrainSeconds = $delivery.drainSeconds
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output "$chain-$rate-tps-run-$round-result.json") -Encoding utf8
    Add-Content -LiteralPath $tableFragment -Value "| $chain | $rate | $round | $($summary.tps) | $($summary.p95) | $($summary.p99) | $($summary.errorPct) | $($outbox.maxBacklog) | $($outbox.drainSeconds) | $($delivery.maxBacklog) | $($delivery.drainSeconds) |" -Encoding utf8
}

@"
# Measured end-to-end performance evidence ($stamp)

This file is generated by `scripts/performance/run-suite.ps1`; it is not a
claim until all requested rounds finish. A dedicated, matching
`performance.e2e` endpoint was created or refreshed before this run.

| chain | target TPS | round | actual TPS | P95 ms | P99 ms | error % | Outbox max | Outbox clear s | Delivery max | Delivery clear s |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
"@ | Set-Content -LiteralPath $tableFragment -Encoding utf8

Ensure-PerformanceEndpoint
foreach ($rate in $Rates) {
    foreach ($round in 1..$Repetitions) {
        $name = "e2e-$rate-tps-run-$round"
        Snapshot "$name-before"
        $env:EVENTRELAY_PROFILE = "e2e"
        $env:EVENTRELAY_EVENT_TYPE = $eventType
        $env:EVENTRELAY_RATE = [string]$rate
        $env:EVENTRELAY_DURATION = $Duration
        $summaryPath = Join-Path $output "$name-summary.json"
        & $K6 run --summary-export $summaryPath (Join-Path $PSScriptRoot "load.js") *>&1 |
            Tee-Object -FilePath (Join-Path $output "$name-console.txt")
        if ($LASTEXITCODE -ne 0) { throw "k6 failed for $name" }
        $outboxDrain = Wait-Drained "eventrelay-publisher" "webhook_outbox_pending"
        $outboxPrometheusMax = Prometheus-Max "webhook_outbox_pending" $Duration
        if ($null -ne $outboxPrometheusMax) { $outboxDrain.maxBacklog = [Math]::Max($outboxDrain.maxBacklog, $outboxPrometheusMax) }
        $deliveryDrain = Wait-Drained "eventrelay-worker" "webhook_delivery_ready"
        Snapshot "$name-after"
        Add-Result "e2e" $rate $round (Read-K6Summary $summaryPath "e2e") $outboxDrain $deliveryDrain
    }
}

Write-Host "Raw k6, Prometheus, MySQL, RabbitMQ, and container evidence: $output"
Write-Host "Final measured performance report: $tableFragment"
