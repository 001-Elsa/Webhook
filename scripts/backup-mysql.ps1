param([string]$OutputDirectory = "backups")
$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$directory = Join-Path $root $OutputDirectory
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
$path = Join-Path $directory "eventrelay-$stamp.sql"
docker compose exec -T mysql mysqldump `
    -u$env:EVENTRELAY_MYSQL_USER -p$env:EVENTRELAY_MYSQL_PASSWORD `
    --single-transaction --routines --triggers --set-gtid-purged=OFF event_relay |
    Set-Content -LiteralPath $path -Encoding utf8
if ($LASTEXITCODE -ne 0) { throw "mysqldump failed" }
$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash
"$hash  $(Split-Path $path -Leaf)" | Set-Content -LiteralPath "$path.sha256" -Encoding ascii
Write-Host "Backup written to $path"
