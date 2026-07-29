# Fault drill evidence template

Use with `scripts/fault-drill.ps1`. Attach Prometheus / queue depth / trace links.

| Field | Value |
|---|---|
| Drill ID | `fault-drill-YYYYMMDDTHHMMSSZ` |
| Operator |  |
| Git commit |  |
| Injection start (UTC) |  |
| Injection type | restart MySQL / Redis / RabbitMQ / role |
| Expected invariant |  |
| Recovery observed (UTC) |  |
| Recovery seconds |  |
| Outcome | RECOVERED / FAILED |
| Correlated evidence paths | Prometheus snapshot / outbox count / Jaeger trace |
| Notes |  |

## Services restarted

| Service | StartedAt (UTC) | RecoveredAt (UTC) | Result |
|---|---|---|---|
|  |  |  |  |
