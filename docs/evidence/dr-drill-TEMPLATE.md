# DR drill evidence template

Fill every field. Empty timestamps mean the drill is incomplete — do not claim
measured RPO/RTO.

| Field | Value |
|---|---|
| Drill ID | `dr-drill-YYYYMMDDTHHMMSSZ` |
| Operator |  |
| Git commit |  |
| Environment | compose / staging / isolated restore target |
| Backup file |  |
| Backup SHA-256 |  |
| Backup cutoff (UTC) |  |
| Restore start (UTC) |  |
| Restore complete (UTC) |  |
| Readiness recovered (UTC) |  |
| Smoke test passed (UTC) |  |
| Traffic reopened (UTC) |  |
| Measured RPO | _(pending)_ |
| Measured RTO | _(pending)_ |
| Notes / anomalies |  |

## Checklist

- [ ] Checksum verified before restore
- [ ] Flyway validate / schema check
- [ ] Row-count / orphan checks
- [ ] Outbox drain observed
- [ ] Delivery recovery observed
- [ ] Receiver dedup of repeated Delivery IDs confirmed
- [ ] `-ConfirmDestroy` / `-ConfirmRestore` only used on intentional targets
