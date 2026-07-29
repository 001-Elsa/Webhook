# Horizontal scaling evidence template

Use `scripts/performance/run-scaling.ps1` to scale `eventrelay-worker` (and
optionally publisher) via Compose and record curves. Do not claim linear scale
without three rounds per replica count.

| replicas (worker) | rate | round | TPS | P95 | P99 | error% | backlog | notes |
|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 1 |  | 1 |  |  |  |  |  |  |
| 1 |  | 2 |  |  |  |  |  |  |
| 1 |  | 3 |  |  |  |  |  |  |
| 2 |  | 1 |  |  |  |  |  |  |
| 2 |  | 2 |  |  |  |  |  |  |
| 2 |  | 3 |  |  |  |  |  |  |
| 4 |  | 1 |  |  |  |  |  |  |
| 4 |  | 2 |  |  |  |  |  |  |
| 4 |  | 3 |  |  |  |  |  |  |

| Field | Value |
|---|---|
| Git commit |  |
| Image digest |  |
| Publisher replicas |  |
| CPU/memory limits |  |
| MySQL / RabbitMQ versions |  |
| Evidence directory | `docs/evidence/` / `data/performance/` |
