# Authentication roadmap

## Current (primary)

API Key authentication is the primary and shipped control-plane auth model:

- `X-App-Id` + `X-Api-Key` (PBKDF2-hashed at rest)
- Key ID, multi-key coexistence, scopes, expiry, revocation
- Dual-key rotation windows for producers and admins

Webhook receivers verify HMAC signatures on the raw body; that path is
independent of control-plane auth.

## Planned (not implemented)

The following are **roadmap items**, not current product claims:

| Mechanism | Intent | Status |
|---|---|---|
| OIDC / OAuth2 bearer tokens | SSO for operators and service accounts | Planned |
| mTLS for ingress / service mesh | Strong service identity between roles | Planned |
| Short-lived workload identity | Replace long-lived API keys in Kubernetes | Planned |

Until those land, treat API Key as the only supported authentication for
`/api/**` and do not claim OIDC completeness in deployment docs or resumes.
