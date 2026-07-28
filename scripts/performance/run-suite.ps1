param(
    [string]$K6 = "k6",
    [int[]]$Rates = @(25, 50, 100, 200),
    [int]$Repetitions = 3,
    [string]$Duration = "60s",
    [string]$OutputDirectory = "data/performance"
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$output = Join-Path $root $OutputDirectory
New-Item -ItemType Directory -Force -Path $output | Out-Null

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

foreach ($rate in $Rates) {
    foreach ($run in 1..$Repetitions) {
        foreach ($profile in @("ingress", "e2e")) {
            $name = "$profile-$rate-tps-run-$run"
            Snapshot "$name-before"
            $env:EVENTRELAY_PROFILE = $profile
            $env:EVENTRELAY_RATE = [string]$rate
            $env:EVENTRELAY_DURATION = $Duration
            & $K6 run --summary-export (Join-Path $output "$name-summary.json") `
                (Join-Path $PSScriptRoot "load.js") *>&1 |
                Tee-Object -FilePath (Join-Path $output "$name-console.txt")
            if ($LASTEXITCODE -ne 0) { throw "k6 failed for $name" }
            Snapshot "$name-after"
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
    }
}

Write-Host "Raw evidence written to $output. Report medians; do not claim a target until all three runs pass."
