<#
.SYNOPSIS
  Backup → optional restore dry-run validation → write DR drill evidence markdown.

.NOTES
  Safe defaults: never destroys or restores the live DB unless -ConfirmDestroy
  is passed (which then also requires restore confirmation semantics).
#>
param(
    [string]$OutputDirectory = "backups",
    [string]$EvidenceDirectory = "docs/evidence",
    [switch]$ConfirmDestroy,
    [switch]$SkipRestoreValidation
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
$evidenceDir = Join-Path $root $EvidenceDirectory
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null
$evidencePath = Join-Path $evidenceDir "dr-drill-$stamp.md"
$template = Join-Path $evidenceDir "dr-drill-TEMPLATE.md"

Write-Host "==> Creating MySQL logical backup"
& (Join-Path $PSScriptRoot "backup-mysql.ps1") -OutputDirectory $OutputDirectory
if ($LASTEXITCODE -ne 0) { throw "backup failed" }

$backupDir = Join-Path $root $OutputDirectory
$backup = Get-ChildItem -LiteralPath $backupDir -Filter "eventrelay-*.sql" |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1
if (-not $backup) { throw "No backup file found under $backupDir" }
$hashFile = "$($backup.FullName).sha256"
$hash = if (Test-Path -LiteralPath $hashFile) {
    (Get-Content -LiteralPath $hashFile -TotalCount 1).Split(" ", 2)[0]
} else {
    (Get-FileHash -Algorithm SHA256 -LiteralPath $backup.FullName).Hash
}

$restoreStart = ""
$restoreComplete = ""
$notes = @()

if (-not $SkipRestoreValidation) {
    Write-Host "==> Restore dry-run validation (checksum + file readability)"
    if (-not (Test-Path -LiteralPath $backup.FullName)) {
        throw "Backup missing: $($backup.FullName)"
    }
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $backup.FullName).Hash
    if ($actual -ne $hash) {
        throw "Backup checksum mismatch"
    }
    $size = (Get-Item -LiteralPath $backup.FullName).Length
    if ($size -lt 100) {
        throw "Backup file suspiciously small ($size bytes)"
    }
    $notes += "Checksum OK; backup size=$size bytes; restore not applied (safe default)."
}

if ($ConfirmDestroy) {
    Write-Host "==> -ConfirmDestroy set: restoring into Compose MySQL (DESTRUCTIVE)"
    $restoreStart = (Get-Date).ToUniversalTime().ToString("o")
    & (Join-Path $PSScriptRoot "restore-mysql.ps1") -BackupFile $backup.FullName -ConfirmRestore
    if ($LASTEXITCODE -ne 0) { throw "restore failed" }
    $restoreComplete = (Get-Date).ToUniversalTime().ToString("o")
    $notes += "Live restore executed with -ConfirmDestroy/-ConfirmRestore."
} else {
    $notes += "Skipped destructive restore. Re-run with -ConfirmDestroy to apply backup."
}

$templateBody = if (Test-Path -LiteralPath $template) {
    Get-Content -LiteralPath $template -Raw
} else {
    "# DR drill evidence`n"
}

$body = $templateBody `
    -replace 'dr-drill-YYYYMMDDTHHMMSSZ', "dr-drill-$stamp" `
    -replace '\| Backup file \|  \|', "| Backup file | $($backup.Name) |" `
    -replace '\| Backup SHA-256 \|  \|', "| Backup SHA-256 | $hash |" `
    -replace '\| Backup cutoff \(UTC\) \|  \|', "| Backup cutoff (UTC) | $stamp |"

if ($restoreStart) {
    $body = $body -replace '\| Restore start \(UTC\) \|  \|', "| Restore start (UTC) | $restoreStart |"
}
if ($restoreComplete) {
    $body = $body -replace '\| Restore complete \(UTC\) \|  \|', "| Restore complete (UTC) | $restoreComplete |"
}

$body = $body.TrimEnd() + "`n`n## Orchestrator notes`n`n- " + ($notes -join "`n- ") + "`n"
Set-Content -LiteralPath $evidencePath -Value $body -Encoding utf8
Write-Host "DR drill evidence written to $evidencePath"
Write-Host "Measured RPO/RTO remain pending until readiness + smoke timestamps are filled."
