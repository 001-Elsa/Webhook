$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$tools = Join-Path $root ".tools"
$mavenHome = Join-Path $tools "apache-maven-3.9.9"
$zip = Join-Path $tools "apache-maven-3.9.9-bin.zip"

New-Item -ItemType Directory -Force -Path $tools | Out-Null

if ((Test-Path $zip) -and ((Get-Item $zip).Length -eq 0)) {
    Remove-Item -LiteralPath $zip -Force
}

if (!(Test-Path $zip)) {
    $urls = @(
        "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip",
        "https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip"
    )
    foreach ($url in $urls) {
        try {
            Write-Host "Downloading Maven from $url"
            curl.exe -L --fail --retry 3 --connect-timeout 20 -o $zip $url
            if ($LASTEXITCODE -ne 0) {
                throw "curl failed with exit code $LASTEXITCODE"
            }
            break
        } catch {
            if (Test-Path $zip) { Remove-Item -LiteralPath $zip -Force }
        }
    }
}

if (!(Test-Path $zip)) {
    throw "Unable to download Maven into $zip"
}

if (!(Test-Path $mavenHome)) {
    Expand-Archive -Path $zip -DestinationPath $tools -Force
}

& (Join-Path $mavenHome "bin/mvn.cmd") -version
