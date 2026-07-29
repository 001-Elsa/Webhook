-- Circuit HALF_OPEN, replay preview summary, tenant attempt retention.
ALTER TABLE webhook_endpoints
    ADD COLUMN circuit_state VARCHAR(20) NOT NULL DEFAULT 'CLOSED',
    ADD COLUMN half_open_probes INT NOT NULL DEFAULT 0,
    ADD COLUMN half_open_max_probes INT NOT NULL DEFAULT 1;

UPDATE webhook_endpoints
   SET circuit_state = 'OPEN'
 WHERE circuit_open_until IS NOT NULL
   AND circuit_open_until > CURRENT_TIMESTAMP(6);

ALTER TABLE tenant_quotas
    ADD COLUMN attempt_retention_days INT NOT NULL DEFAULT 30;

ALTER TABLE replay_jobs
    ADD COLUMN result_summary_json TEXT NULL;
