param(
    [string]$K6 = "k6",
    [int[]]$Rates = @(50, 100, 200, 400),
    [int]$Repetitions = 3,
    [string]$Duration = "60s",
    [string]$OutputDirectory = "data/performance",
    [string]$EvidenceDirectory = "docs/evidence"
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$output = Join-Path $root $OutputDirectory
$evidence = Join-Path $root $EvidenceDirectory
New-Item -ItemType Directory -Force -Path $output | Out-Null
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
$tableFragment = Join-Path $evidence "perf-suite-$stamp.md"

function Snapshot([string]$name) {
    $path = Join-Path $output "$name.prom"
    docker compose exec -T eventrelay-api curl -fsS http://localhost:8083/actuator/prometheus |
        Set-Content -LiteralPath $path -Encoding utf8
    docker stats --no-stream --format "{{json .}}" |
        Set-Content -LiteralPath (Join-Path $output "$name-docker-stats.jsonl") -Encoding utf8
    docker compose exec -T mysql mysql -u$env:EVENTRELAY_MYSQL_USER `
        -p$env:EVENTRELAY_MYSQL_PASSWORD event_relay -e `
        "SHOW GLOBAL STATUS LIKE 'Threads_waiting'; SELECT * FROM performance_schema.data_lock_waits;" |
        Set-Content -LiteralPath (Join-Path $output "$name-mysql.txt") -Encoding utf8
}

function Wait-Drained([string]$service, [string]$metric) {
    for ($i = 0; $i -lt 600; $i++) {
        $text = docker compose exec -T $service curl -fsS http://localhost:8083/actuator/prometheus
        $line = ($text | Select-String -Pattern "^$metric\s+").Line
        if ($line -and [double]($line -split '\s+')[1] -le 0) { return }
        Start-Sleep -Milliseconds 500
    }
    throw "$metric did not drain"
}

function Read-K6Summary([string]$summaryPath) {
    if (-not (Test-Path -LiteralPath $summaryPath)) {
        return [pscustomobject]@{ tps = ""; p95 = ""; p99 = ""; errorPct = "" }
    }
    $json = Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json
    $metrics = $json.metrics
    $http = $metrics.http_req_duration
    $rate = $metrics.http_reqs
    $fails = $metrics.http_req_failed
    return [pscustomobject]@{
        tps = if ($rate -and $rate.rate) { [math]::Round($rate.rate, 2) } else { "" }
        p95 = if ($http -and $http.'p(95)') { [math]::Round($http.'p(95)', 2) } else { "" }
        p99 = if ($http -and $http.'p(99)') { [math]::Round($http.'p(99)', 2) } else { "" }
        errorPct = if ($fails -and $fails.value -ne $null) { [math]::Round(100.0 * $fails.value, 3) } else { "" }
    }
}

function Append-MarkdownRow([string]$chain, [int]$rate, [int]$run, $summary, $backlog) {
    $line = "| $chain | $rate | $run | $($summary.tps) | $($summary.p95) | $($summary.p99) | $($summary.errorPct) | $backlog |"
    Add-Content -LiteralPath $tableFragment -Value $line -Encoding utf8
}

@"
# Performance suite fragment ($stamp)

Pending measured evidence until three rounds per rate complete. Columns match
docs/performance-report.md.

| chain | rate | round | TPS | P95 | P99 | error% | backlog |
|---|---:|---:|---:|---:|---:|---:|---:|
"@ | Set-Content -LiteralPath $tableFragment -Encoding utf8

foreach ($rate in $Rates) {
    foreach ($run in 1..$Repetitions) {
        foreach ($profile in @("ingress", "e2e")) {
            $name = "$profile-$rate-tps-run-$run"
            Snapshot "$name-before"
            $env:EVENTRELAY_PROFILE = $profile
            $env:EVENTRELAY_RATE = [string]$rate
            $env:EVENTRELAY_DURATION = $Duration
            $summaryPath = Join-Path $output "$name-summary.json"
            & $K6 run --summary-export $summaryPath `
                (Join-Path $PSScriptRoot "load.js") *>&1 |
                Tee-Object -FilePath (Join-Path $output "$name-console.txt")
            if ($LASTEXITCODE -ne 0) { throw "k6 failed for $name" }
            Snapshot "$name-after"
            $summary = Read-K6Summary $summaryPath
            Append-MarkdownRow $profile $rate $run $summary ""
        }

        # Outbox-only: accumulate confirmed work with publisher stopped, then time backlog drain.
        docker compose stop eventrelay-publisher
        $env:EVENTRELAY_PROFILE = "worker-seed"
        $env:EVENTRELAY_RATE = [string]$rate
        $env:EVENTRELAY_DURATION = $Duration
        & $K6 run --summary-export (Join-Path $output "outbox-$rate-run-$run-seed.json") `
            (Join-Path $PSScriptRoot "load.js")
        Snapshot "outbox-$rate-run-$run-before"
        $started = Get-Date
        docker compose start eventrelay-publisher
        Wait-Drained "eventrelay-publisher" "webhook_outbox_pending"
        $elapsed = ((Get-Date) - $started).TotalSeconds
        [pscustomobject]@{ chain="outbox"; rate=$rate; run=$run; drainSeconds=$elapsed } |
            ConvertTo-Json | Set-Content (Join-Path $output "outbox-$rate-run-$run-result.json")
        Append-MarkdownRow "outbox" $rate $run ([pscustomobject]@{
            tps = ""; p95 = ""; p99 = ""; errorPct = ""
        }) ("drainSeconds=" + [math]::Round($elapsed, 2))

        # Worker-only: fill RabbitMQ while workers are stopped, then measure terminal drain.
        docker compose stop eventrelay-worker
        $env:EVENTRELAY_PROFILE = "worker-seed"
        & $K6 run --summary-export (Join-Path $output "worker-$rate-run-$run-seed.json") `
            (Join-Path $PSScriptRoot "load.js")
        Start-Sleep -Seconds 3
        Snapshot "worker-$rate-run-$run-before"
        $started = Get-Date
        docker compose start eventrelay-worker
        Wait-Drained "eventrelay-worker" "webhook_delivery_ready"
        $elapsed = ((Get-Date) - $started).TotalSeconds
        [pscustomobject]@{ chain="worker"; rate=$rate; run=$run; drainSeconds=$elapsed } |
            ConvertTo-Json | Set-Content (Join-Path $output "worker-$rate-run-$run-result.json")
        Append-MarkdownRow "worker" $rate $run ([pscustomobject]@{
            tps = ""; p95 = ""; p99 = ""; errorPct = ""
        }) ("drainSeconds=" + [math]::Round($elapsed, 2))
    }
}

Write-Host "Raw evidence written to $output"
Write-Host "Markdown table fragment written to $tableFragment"
Write-Host "Report medians; do not claim a target until all three runs pass."
