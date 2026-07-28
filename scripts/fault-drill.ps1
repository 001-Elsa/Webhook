$ErrorActionPreference = "Stop"

function Wait-Healthy([string]$service) {
    for ($i = 0; $i -lt 60; $i++) {
        $status = docker compose ps --format json $service | ConvertFrom-Json
        if ($status.Health -eq "healthy" -or $status.State -eq "running") { return }
        Start-Sleep -Seconds 2
    }
    throw "$service did not recover in time"
}

$services = @("rabbitmq", "redis", "mysql", "eventrelay-api", "eventrelay-publisher",
    "eventrelay-worker", "eventrelay-scheduler")
$evidence = @()
foreach ($service in $services) {
    $started = Get-Date
    Write-Host "Restarting $service"
    docker compose restart $service
    Wait-Healthy $service
    $recovered = Get-Date
    $evidence += [pscustomobject]@{
        Fault = "restart-$service"
        Expected = "service recovers without message loss"
        StartedAt = $started.ToUniversalTime().ToString("o")
        RecoveredAt = $recovered.ToUniversalTime().ToString("o")
        RecoverySeconds = [math]::Round(($recovered - $started).TotalSeconds, 2)
        Result = "RECOVERED"
    }
}

$health = docker compose exec -T eventrelay-api curl -fsS http://localhost:8083/actuator/health |
    ConvertFrom-Json
if ($health.status -ne "UP") { throw "EventRelay health check failed" }
$report = Join-Path $PSScriptRoot "..\docs\fault-drill-latest.json"
$evidence | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $report -Encoding utf8
Write-Host "Fault drill completed; inspect Prometheus alerts and outbox backlog before declaring success."
Write-Host "Evidence written to $report"
