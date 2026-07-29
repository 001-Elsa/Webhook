-- Scheduler crashes must not strand a replay job in RUNNING indefinitely.
ALTER TABLE replay_jobs
    ADD COLUMN locked_by VARCHAR(120) NULL,
    ADD COLUMN locked_until DATETIME(6) NULL,
    ADD COLUMN heartbeat_at DATETIME(6) NULL,
    ADD INDEX idx_replay_job_recovery (status, locked_until, created_at);
