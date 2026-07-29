# EventRelay ops CLI

Thin, dependency-free Python CLI for control-plane operations. Requires API Key
auth (`EVENTRELAY_APP_ID`, `EVENTRELAY_API_KEY`) and optional `EVENTRELAY_URL`
(default `http://localhost:8080`).

## Commands

```powershell
$env:EVENTRELAY_URL = "http://localhost:8080"
$env:EVENTRELAY_APP_ID = "platform-admin"
$env:EVENTRELAY_API_KEY = "<admin-api-key>"

# List deliveries for the authenticated tenant
python cli/eventrelay.py deliveries

# Create an async ReplayJob (Dry Run first)
python cli/eventrelay.py replay --dry-run --max 100

# Read-only incident diagnosis for one delivery
python cli/eventrelay.py diagnose 12345
```

Diagnosis uses rule-based (and optional AI) control-plane advisors with
structured keyword runbook retrieval — it never mutates delivery state.
