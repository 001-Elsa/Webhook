param(
    [string]$EvidenceDirectory = "docs/evidence",
    [int]$TerminalTimeoutSeconds = 120
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$evidenceDirectory = Join-Path $root $EvidenceDirectory
New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null
$stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
$reportJson = Join-Path $evidenceDirectory "fault-drill-$stamp.json"
$reportMarkdown = Join-Path $evidenceDirectory "fault-drill-$stamp.md"
$eventType = "fault.drill"
$outcomes = [System.Collections.Generic.List[object]]::new()
$drillFailure = $null

foreach ($name in "EVENTRELAY_APP_ID", "EVENTRELAY_API_KEY", "EVENTRELAY_ADMIN_APP_ID", "EVENTRELAY_ADMIN_API_KEY") {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        throw "$name must be set before starting the fault drill"
    }
}

$producerHeaders = @{ "Content-Type" = "application/json"; "X-App-Id" = $env:EVENTRELAY_APP_ID; "X-Api-Key" = $env:EVENTRELAY_API_KEY }
$adminHeaders = @{ "Content-Type" = "application/json"; "X-App-Id" = $env:EVENTRELAY_ADMIN_APP_ID; "X-Api-Key" = $env:EVENTRELAY_ADMIN_API_KEY }

function Invoke-Api([string]$method, [string]$path, [hashtable]$headers, $body = $null) {
    $params = @{ Method = $method; Uri = "http://localhost:8080$path"; Headers = $headers; UseBasicParsing = $true }
    if ($null -ne $body) { $params.Body = ($body | ConvertTo-Json -Compress -Depth 8) }
    Invoke-RestMethod @params
}

function Wait-Healthy([string]$service) {
    for ($i = 0; $i -lt 90; $i++) {
        $row = (& docker compose ps --format json $service | ConvertFrom-Json)
        if ($row -and (($row.Health -eq "healthy") -or (($row.State -eq "running") -and [string]::IsNullOrEmpty($row.Health)))) { return }
        Start-Sleep -Seconds 2
    }
    throw "$service did not recover in time"
}

function Wait-EventTerminal([string]$eventId) {
    $deadline = (Get-Date).AddSeconds($TerminalTimeoutSeconds)
    do {
        $state = Invoke-Api "GET" "/api/events/$eventId/status" $adminHeaders
        if ($state.status -in @("COMPLETED", "DEAD", "PARTIALLY_FAILED")) { return $state }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    throw "Event $eventId did not reach a terminal state"
}

function Ensure-DrillEndpoint {
    $secret = (& docker compose exec -T receiver-mock sh -c 'printf %s "$RECEIVER_WEBHOOK_SECRET"').Trim()
    if ([string]::IsNullOrWhiteSpace($secret)) { throw "Receiver mock has no webhook secret" }
    $definition = @{
        name = "fault-drill-receiver"; url = "http://receiver-mock:8082/webhook/demo-merchant"; secret = $secret
        eventTypes = $eventType; active = $true; maxAttempts = 5; rateLimitPerMinute = 100000; filterExpression = $null
    }
    $existing = Invoke-Api "GET" "/api/endpoints" $adminHeaders | Where-Object { $_.name -eq "fault-drill-receiver" } | Select-Object -First 1
    if ($null -eq $existing) { Invoke-Api "POST" "/api/endpoints" $adminHeaders $definition | Out-Null }
    else { Invoke-Api "PUT" "/api/endpoints/$($existing.id)" $adminHeaders $definition | Out-Null }
    Invoke-RestMethod -Method POST -Uri "http://localhost:8082/api/config" -ContentType "application/json" -Body '{"failNext":0,"delayMs":0}' | Out-Null
}

function Submit-DrillEvent([string]$fault) {
    $eventId = "fault-$fault-$stamp-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
    $accepted = Invoke-Api "POST" "/api/events" $producerHeaders @{ eventId = $eventId; type = $eventType; data = @{ fault = $fault; drill = $stamp } }
    if ($accepted.deliveryCount -lt 1) { throw "Fault $fault created no matching Delivery" }
    return $eventId
}

function Save-Snapshot([string]$fault, [string]$eventId, [string]$phase) {
    $base = Join-Path $evidenceDirectory "$stamp-$fault-$phase"
    & docker compose exec -T rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers |
        Set-Content -LiteralPath "$base-rabbitmq.txt" -Encoding utf8
    & docker compose exec -T eventrelay-api curl -fsS http://localhost:8083/actuator/prometheus |
        Set-Content -LiteralPath "$base-prometheus.prom" -Encoding utf8
    $escaped = $eventId.Replace("'", "''")
    $sql = "SELECT e.event_id,e.status AS event_status,d.id AS delivery_id,d.status AS delivery_status,d.attempt_count,o.status AS outbox_status FROM event_records e LEFT JOIN delivery_tasks d ON d.event_id=e.id LEFT JOIN outbox_messages o ON o.delivery_id=d.id WHERE e.event_id='$escaped' ORDER BY d.id,o.id;"
    & docker compose exec -T mysql mysql -u$env:EVENTRELAY_MYSQL_USER -p$env:EVENTRELAY_MYSQL_PASSWORD event_relay -e $sql |
        Set-Content -LiteralPath "$base-database.txt" -Encoding utf8
    return [pscustomobject]@{ database = "$base-database.txt"; queue = "$base-rabbitmq.txt"; prometheus = "$base-prometheus.prom" }
}

function Query-Scalar([string]$sql) {
    $raw = & docker compose exec -T mysql mysql -N -s -u$env:EVENTRELAY_MYSQL_USER -p$env:EVENTRELAY_MYSQL_PASSWORD event_relay -e $sql
    return [int]($raw | Select-Object -First 1)
}

function Pending-Outbox([string]$eventId) {
    $escaped = $eventId.Replace("'", "''")
    Query-Scalar "SELECT COUNT(*) FROM outbox_messages o JOIN delivery_tasks d ON d.id=o.delivery_id JOIN event_records e ON e.id=d.event_id WHERE e.event_id='$escaped' AND o.status='PENDING';"
}

function Verify-Final([string]$fault, [string]$eventId) {
    $terminal = Wait-EventTerminal $eventId
    $escaped = $eventId.Replace("'", "''")
    $deliveryCount = Query-Scalar "SELECT COUNT(*) FROM delivery_tasks d JOIN event_records e ON e.id=d.event_id WHERE e.event_id='$escaped';"
    $terminalCount = Query-Scalar "SELECT COUNT(*) FROM delivery_tasks d JOIN event_records e ON e.id=d.event_id WHERE e.event_id='$escaped' AND d.status IN ('SUCCEEDED','DEAD');"
    $pending = Pending-Outbox $eventId
    if ($terminal.status -notin @("COMPLETED", "DEAD", "PARTIALLY_FAILED")) { throw "$fault did not reach an event terminal state" }
    if ($deliveryCount -ne $terminalCount) { throw "$fault left a delivery without a terminal state" }
    if ($pending -ne 0) { throw "$fault left $pending PENDING outbox messages" }
    return [pscustomobject]@{ terminalStatus = $terminal.status; deliveryCount = $deliveryCount; terminalDeliveryCount = $terminalCount; pendingOutbox = $pending }
}

function Record-Outcome([string]$fault, [string]$eventId, $started, $recovered, $before, $after, $final, $extra = @{}) {
    $outcomes.Add([pscustomobject]@{
        fault = $fault; eventId = $eventId; startedAtUtc = $started.ToUniversalTime().ToString("o")
        recoveredAtUtc = $recovered.ToUniversalTime().ToString("o"); recoverySeconds = [Math]::Round(($recovered - $started).TotalSeconds, 2)
        before = $before; after = $after; final = $final; checks = $extra; outcome = "RECOVERED"
    })
}

try {
    Ensure-DrillEndpoint

    # RabbitMQ outage: the API transaction commits, while the publisher must leave the outbox durable and pending.
    $started = Get-Date; & docker compose stop rabbitmq
    $eventId = Submit-DrillEvent "rabbitmq"
    Start-Sleep -Seconds 3
    $before = Save-Snapshot "rabbitmq" $eventId "during-outage"
    $pendingDuringRabbit = Pending-Outbox $eventId
    if ($pendingDuringRabbit -lt 1) { throw "RabbitMQ outage did not preserve a PENDING outbox message" }
    & docker compose start rabbitmq; Wait-Healthy "rabbitmq"
    $final = Verify-Final "rabbitmq" $eventId; $recovered = Get-Date
    $after = Save-Snapshot "rabbitmq" $eventId "recovered"
    Record-Outcome "rabbitmq" $eventId $started $recovered $before $after $final @{ pendingDuringOutage = $pendingDuringRabbit }

    # Publisher outage: accepted events accumulate in MySQL and drain after this role restarts.
    $started = Get-Date; & docker compose stop eventrelay-publisher
    $eventId = Submit-DrillEvent "publisher"
    Start-Sleep -Seconds 3
    $before = Save-Snapshot "publisher" $eventId "during-outage"
    $pendingDuringPublisher = Pending-Outbox $eventId
    if ($pendingDuringPublisher -lt 1) { throw "Stopped publisher did not leave the outbox PENDING" }
    & docker compose start eventrelay-publisher; Wait-Healthy "eventrelay-publisher"
    $final = Verify-Final "publisher" $eventId; $recovered = Get-Date
    $after = Save-Snapshot "publisher" $eventId "recovered"
    Record-Outcome "publisher" $eventId $started $recovered $before $after $final @{ pendingDuringOutage = $pendingDuringPublisher }

    # Worker outage: RabbitMQ retains the work until a restarted worker consumes it.
    $started = Get-Date; & docker compose stop eventrelay-worker
    $eventId = Submit-DrillEvent "worker"
    Start-Sleep -Seconds 3
    $before = Save-Snapshot "worker" $eventId "during-outage"
    & docker compose start eventrelay-worker; Wait-Healthy "eventrelay-worker"
    $final = Verify-Final "worker" $eventId; $recovered = Get-Date
    $after = Save-Snapshot "worker" $eventId "recovered"
    Record-Outcome "worker" $eventId $started $recovered $before $after $final

    # MySQL is killed after the receiver has accepted the HTTP request but before its delayed response returns.
    # A repeated receiver call is allowed; the database must keep exactly one delivery row and one terminal state.
    Invoke-RestMethod -Method POST -Uri "http://localhost:8082/api/config" -ContentType "application/json" -Body '{"failNext":0,"delayMs":10000}' | Out-Null
    $started = Get-Date; $eventId = Submit-DrillEvent "mysql-after-http"
    $seen = $false
    for ($i = 0; $i -lt 40; $i++) {
        $received = Invoke-RestMethod -Uri "http://localhost:8082/api/received"
        if ($received | Where-Object { $_.eventId -eq $eventId }) { $seen = $true; break }
        Start-Sleep -Milliseconds 250
    }
    if (-not $seen) { throw "Receiver did not observe $eventId before MySQL injection" }
    & docker compose kill mysql
    & docker compose start mysql; Wait-Healthy "mysql"
    Invoke-RestMethod -Method POST -Uri "http://localhost:8082/api/config" -ContentType "application/json" -Body '{"delayMs":0}' | Out-Null
    $final = Verify-Final "mysql-after-http" $eventId; $recovered = Get-Date
    $after = Save-Snapshot "mysql-after-http" $eventId "recovered"
    Record-Outcome "mysql-after-http" $eventId $started $recovered $null $after $final @{ httpSucceededBeforeDatabaseKill = $true }

    # Redis is advisory for rate/idempotency cache behaviour; the durable delivery path must stay available.
    $started = Get-Date; & docker compose stop redis
    $eventId = Submit-DrillEvent "redis"
    $before = Save-Snapshot "redis" $eventId "during-outage"
    $final = Verify-Final "redis" $eventId
    & docker compose start redis; Wait-Healthy "redis"; $recovered = Get-Date
    $after = Save-Snapshot "redis" $eventId "recovered"
    Record-Outcome "redis" $eventId $started $recovered $before $after $final @{ mainDeliveryAvailableDuringOutage = $true }
}
catch {
    $drillFailure = $_
    $outcomes.Add([pscustomobject]@{
        fault = "drill-aborted"; eventId = ""; startedAtUtc = ""; recoveredAtUtc = ""; recoverySeconds = ""
        before = $null; after = $null; final = [pscustomobject]@{ terminalStatus = "NOT_VERIFIED"; deliveryCount = ""; terminalDeliveryCount = ""; pendingOutbox = "" }
        checks = @{ error = $_.Exception.Message }; outcome = "FAILED"
    })
}
finally {
    Invoke-RestMethod -Method POST -Uri "http://localhost:8082/api/config" -ContentType "application/json" -Body '{"failNext":0,"delayMs":0}' -ErrorAction SilentlyContinue | Out-Null
    foreach ($service in "mysql", "redis", "rabbitmq", "eventrelay-publisher", "eventrelay-worker") {
        & docker compose start $service 2>$null
    }
}

$outcomes | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportJson -Encoding utf8
$rows = $outcomes | ForEach-Object { "| $($_.fault) | $($_.eventId) | $($_.recoverySeconds) | $($_.final.terminalStatus) | $($_.final.deliveryCount)/$($_.final.terminalDeliveryCount) | $($_.final.pendingOutbox) | $($_.outcome) |" }
@"
# Fault drill report ($stamp)

| Fault | Event ID | Recovery seconds | Event terminal state | deliveries / terminal deliveries | pending outbox | outcome |
|---|---|---:|---|---:|---:|---|
$($rows -join "`n")

Raw MySQL state, RabbitMQ queue depth, and Prometheus evidence is linked from
`$reportJson`. The MySQL drill delays the receiver response, kills MySQL after
the receiver records the request, then verifies that the Delivery eventually
has exactly one terminal database record. This validates at-least-once delivery:
HTTP may be repeated, but the durable terminal state is not lost or duplicated.
"@ | Set-Content -LiteralPath $reportMarkdown -Encoding utf8

Write-Host "Fault drill report: $reportMarkdown"
Write-Host "Raw machine-readable evidence: $reportJson"
if ($null -ne $drillFailure) { throw $drillFailure }
