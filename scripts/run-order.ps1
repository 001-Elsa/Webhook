$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot "download-maven.ps1")
& (Join-Path $root ".tools/apache-maven-3.9.9/bin/mvn.cmd") -f (Join-Path $root "pom.xml") -pl demo-order-service spring-boot:run
