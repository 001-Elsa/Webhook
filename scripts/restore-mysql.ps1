param(
    [Parameter(Mandatory = $true)][string]$BackupFile,
    [switch]$ConfirmRestore
)
$ErrorActionPreference = "Stop"
if (-not $ConfirmRestore) {
    throw "Restore replaces data in event_relay. Re-run with -ConfirmRestore after verifying the target."
}
$resolved = (Resolve-Path -LiteralPath $BackupFile).Path
$workspace = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if (-not $resolved.StartsWith($workspace, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Backup must be inside the EventRelay workspace"
}
Get-Content -LiteralPath $resolved -Raw |
    docker compose exec -T mysql mysql `
        -u$env:EVENTRELAY_MYSQL_USER -p$env:EVENTRELAY_MYSQL_PASSWORD event_relay
if ($LASTEXITCODE -ne 0) { throw "mysql restore failed" }
Write-Host "Restore completed. Run mvn verify and the smoke test before reopening traffic."
