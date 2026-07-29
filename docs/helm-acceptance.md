# Helm acceptance checklist

Use `scripts/helm-acceptance.ps1` for lint + `helm template` dry-run. Record
evidence timestamps in UTC. Production must override
`networkPolicy.receiverCidrs` (do not leave `0.0.0.0/0`).

| Step | Command / check | Evidence (timestamp + notes) |
|---|---|---|
| Preflight | `helm version`, `kubectl version --client` | Evidence: ____________ |
| Lint | `helm lint deploy/helm/eventrelay` | Evidence: ____________ |
| Template dry-run | `helm template eventrelay deploy/helm/eventrelay -n eventrelay` | Evidence: ____________ |
| Install | `helm upgrade --install eventrelay deploy/helm/eventrelay -n eventrelay --create-namespace` | Evidence: ____________ |
| HPA | `kubectl get hpa -n eventrelay` — api/publisher/worker scaling targets present | Evidence: ____________ |
| PDB | `kubectl get pdb -n eventrelay` — minAvailable/maxUnavailable respected during rollout | Evidence: ____________ |
| NetworkPolicy | `kubectl describe networkpolicy eventrelay -n eventrelay` — receiverCidrs overridden for prod | Evidence: ____________ |
| Migration job | `kubectl get job -n eventrelay` — Flyway/migration Job completed successfully | Evidence: ____________ |
| Rollback | `helm rollback eventrelay <REVISION> -n eventrelay` — previous revision healthy | Evidence: ____________ |

Optional Kind path:

```powershell
.\scripts\helm-acceptance.ps1 -UseKind
# After secrets exist:
.\scripts\helm-acceptance.ps1 -UseKind -Install
```
