$ErrorActionPreference = "Stop"

function Wait-Healthy([string]$service) {
    for ($i = 0; $i -lt 60; $i++) {
        $status = docker compose ps --format json $service | ConvertFrom-Json
        if ($status.Health -eq "healthy" -or $status.State -eq "running") { return }
        Start-Sleep -Seconds 2
    }
    throw "$service did not recover in time"
}

$services = @("rabbitmq", "redis", "mysql", "notification-platform")
foreach ($service in $services) {
    Write-Host "Restarting $service"
    docker compose restart $service
    Wait-Healthy $service
}

$health = Invoke-RestMethod http://localhost:8080/actuator/health
if ($health.status -ne "UP") { throw "EventRelay health check failed" }
Write-Host "Fault drill completed; inspect Prometheus alerts and outbox backlog before declaring success."
