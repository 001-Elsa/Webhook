<#
.SYNOPSIS
  Horizontal scaling helper: scale worker replicas 1/2/4, run worker profile load, record notes.

.NOTES
  Documents the Compose scale workflow. Does not invent TPS numbers — fill
  docs/evidence/horizontal-scaling-TEMPLATE.md (or the stamped copy) after runs.
#>
param(
    [int[]]$WorkerReplicas = @(1, 2, 4),
    [int]$Rate = 100,
    [int]$Repetitions = 3,
    [string]$Duration = "60s",
    [string]$K6 = "k6",
    [string]$EvidenceDirectory = "docs/evidence"
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$evidence = Join-Path $root $EvidenceDirectory
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
$report = Join-Path $evidence "horizontal-scaling-$stamp.md"

@"
# Horizontal scaling run ($stamp)

Worker profile via ``docker compose up -d --scale eventrelay-worker=N``.
Copy measured rows into the template columns after each round.

| replicas (worker) | rate | round | TPS | P95 | P99 | error% | backlog | notes |
|---:|---:|---:|---:|---:|---:|---:|---:|---|
"@ | Set-Content -LiteralPath $report -Encoding utf8

Push-Location $root
try {
    foreach ($replicas in $WorkerReplicas) {
        Write-Host "==> Scaling eventrelay-worker to $replicas"
        docker compose up -d --scale "eventrelay-worker=$replicas" --no-recreate eventrelay-worker
        if ($LASTEXITCODE -ne 0) {
            # Fallback: recreate scaled service set
            docker compose up -d --scale "eventrelay-worker=$replicas" eventrelay-worker
            if ($LASTEXITCODE -ne 0) { throw "Failed to scale workers to $replicas" }
        }
        Start-Sleep -Seconds 15

        foreach ($run in 1..$Repetitions) {
            Write-Host "==> Worker profile rate=$Rate run=$run replicas=$replicas"
            $env:EVENTRELAY_PROFILE = "e2e"
            $env:EVENTRELAY_RATE = [string]$Rate
            $env:EVENTRELAY_DURATION = $Duration
            $summary = Join-Path $root "data\performance\scale-worker-$replicas-rate-$Rate-run-$run.json"
            New-Item -ItemType Directory -Force -Path (Split-Path $summary) | Out-Null
            & $K6 run --summary-export $summary (Join-Path $PSScriptRoot "load.js")
            if ($LASTEXITCODE -ne 0) { throw "k6 failed for replicas=$replicas run=$run" }
            Add-Content -LiteralPath $report -Value "| $replicas | $Rate | $run |  |  |  |  |  | see $summary |" -Encoding utf8
        }
    }
}
finally {
    Pop-Location
}

Write-Host @"

Scaling runs finished. Fill measured TPS/P95/P99 from k6 summaries into:
  $report
  docs/evidence/horizontal-scaling-TEMPLATE.md

Do not claim linear scale until three rounds per replica count succeed.
"@
