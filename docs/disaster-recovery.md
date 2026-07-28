# Disaster recovery

## Objectives

- Target RPO: 15 minutes when binlog shipping is enabled; daily logical dumps
  alone provide only a 24-hour RPO.
- Target RTO: 60 minutes for a regional database restore and role redeployment.

These are design targets, not measured claims. A target becomes an achieved
number only after a timestamped restore drill records backup cutoff, restore
start, readiness, consistency checks and traffic reopening.

## Backup

Use encrypted storage outside the cluster. `scripts/backup-mysql.ps1` creates a
transaction-consistent logical dump and SHA-256 checksum. Production should add
continuous binlog shipping, retention locks and restore credentials separate
from application credentials. RabbitMQ is not the source of truth; recovery
rebuilds missing queue work from MySQL Delivery state and idempotent recovery
Outbox rows.

## Restore drill

1. Provision an isolated MySQL target and verify the backup checksum.
2. Run `scripts/restore-mysql.ps1 -BackupFile ... -ConfirmRestore`.
3. Run Flyway validation, row-count checks and orphan checks.
4. Start Scheduler and Publisher, then Worker, then API.
5. Confirm Outbox drains, due Deliveries recover, and receiver deduplication
   handles any repeated Delivery IDs.
6. Run end-to-end smoke tests and record measured RPO/RTO.
7. Reopen traffic only after tenant isolation and audit queries pass.

Never automatically reverse a Flyway migration during application rollback.
Migrations must remain backward compatible for at least one release window.
