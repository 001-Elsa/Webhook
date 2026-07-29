<#
.SYNOPSIS
  Helm lint/template acceptance checks for EventRelay, with documented install/HPA/rollback steps.

.NOTES
  Safe by default: does not install into a cluster unless -Install is passed.
  Optional Kind cluster bootstrap is available via -UseKind.
#>
param(
    [switch]$UseKind,
    [switch]$Install,
    [string]$ReleaseName = "eventrelay",
    [string]$Namespace = "eventrelay",
    [string]$ValuesFile = "",
    [string]$KindClusterName = "eventrelay"
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$chart = Join-Path $root "deploy\helm\eventrelay"

function Assert-Command([string]$Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command not found on PATH: $Name"
    }
}

Write-Host "==> Checking helm and kubectl"
Assert-Command helm
Assert-Command kubectl

if ($UseKind) {
    Write-Host "==> Optional Kind bootstrap"
    Assert-Command kind
    $existing = kind get clusters 2>$null
    if ($existing -notcontains $KindClusterName) {
        kind create cluster --name $KindClusterName
    }
    kind export kubeconfig --name $KindClusterName | Out-Null
    kubectl cluster-info | Out-Null
}

Write-Host "==> helm lint"
Push-Location $root
try {
    helm lint $chart
    if ($LASTEXITCODE -ne 0) { throw "helm lint failed" }

    Write-Host "==> helm template (dry-run render)"
    $templateArgs = @("template", $ReleaseName, $chart, "--namespace", $Namespace)
    if ($ValuesFile) {
        $templateArgs += @("-f", $ValuesFile)
    }
    & helm @templateArgs | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "helm template failed" }
}
finally {
    Pop-Location
}

Write-Host @"

Acceptance dry-run passed (lint + template).

Documented next steps (manual / with -Install):

1) Install (requires a kube context and secrets):
   kubectl create namespace $Namespace
   # Create secret eventrelay-secrets with MYSQL/REDIS/RABBIT/ENCRYPTION keys first.
   # PRODUCTION: override networkPolicy.receiverCidrs — never leave 0.0.0.0/0.
   helm upgrade --install $ReleaseName deploy/helm/eventrelay -n $Namespace --create-namespace

2) HPA check:
   kubectl get hpa -n $Namespace
   kubectl describe hpa -n $Namespace

3) PDB / NetworkPolicy / migration Job:
   kubectl get pdb,networkpolicy,job -n $Namespace
   kubectl describe networkpolicy eventrelay -n $Namespace

4) Rollback:
   helm history $ReleaseName -n $Namespace
   helm rollback $ReleaseName <REVISION> -n $Namespace

See docs/helm-acceptance.md for the evidence checklist.
"@

if ($Install) {
    Write-Host "==> helm upgrade --install"
    $installArgs = @("upgrade", "--install", $ReleaseName, $chart, "-n", $Namespace, "--create-namespace", "--wait", "--timeout", "10m")
    if ($ValuesFile) {
        $installArgs += @("-f", $ValuesFile)
    }
    & helm @installArgs
    if ($LASTEXITCODE -ne 0) { throw "helm install failed" }
    kubectl get hpa,pdb,networkpolicy,job -n $Namespace
}
