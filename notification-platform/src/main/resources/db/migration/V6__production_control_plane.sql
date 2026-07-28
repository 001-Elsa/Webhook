ALTER TABLE webhook_endpoints
    ADD COLUMN key_version INT NOT NULL DEFAULT 1,
    ADD COLUMN encryption_algorithm VARCHAR(40) NOT NULL DEFAULT 'AES-256-GCM',
    ADD COLUMN secret_updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD COLUMN max_concurrency INT NOT NULL DEFAULT 8,
    ADD COLUMN failure_threshold INT NOT NULL DEFAULT 5,
    ADD COLUMN circuit_cooldown_seconds INT NOT NULL DEFAULT 60,
    ADD COLUMN consecutive_failures INT NOT NULL DEFAULT 0,
    ADD COLUMN circuit_open_until DATETIME(6) NULL,
    ADD COLUMN paused_at DATETIME(6) NULL;

CREATE TABLE api_credentials (
    id BIGINT NOT NULL AUTO_INCREMENT,
    client_id BIGINT NOT NULL,
    key_id VARCHAR(80) NOT NULL,
    api_key_hash VARCHAR(200) NOT NULL,
    scopes VARCHAR(500) NOT NULL,
    status VARCHAR(30) NOT NULL,
    expires_at DATETIME(6) NULL,
    last_used_at DATETIME(6) NULL,
    last_used_ip VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_credential_client FOREIGN KEY (client_id) REFERENCES application_clients(id),
    CONSTRAINT uk_api_credential_key_id UNIQUE (key_id),
    INDEX idx_credential_client_status (client_id, status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tenant_quotas (
    tenant_id VARCHAR(80) NOT NULL,
    ingress_per_second INT NOT NULL DEFAULT 100,
    max_pending_deliveries BIGINT NOT NULL DEFAULT 100000,
    max_concurrent_deliveries INT NOT NULL DEFAULT 32,
    daily_event_limit BIGINT NOT NULL DEFAULT 1000000,
    payload_storage_bytes BIGINT NOT NULL DEFAULT 1073741824,
    scheduling_weight INT NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tenant_daily_usage (
    tenant_id VARCHAR(80) NOT NULL,
    usage_date DATE NOT NULL,
    accepted_events BIGINT NOT NULL DEFAULT 0,
    payload_bytes BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, usage_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE audit_logs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(80) NOT NULL,
    actor_id VARCHAR(80) NOT NULL,
    action VARCHAR(120) NOT NULL,
    resource_type VARCHAR(80) NOT NULL,
    resource_id VARCHAR(120) NULL,
    source_ip VARCHAR(64) NULL,
    trace_id VARCHAR(80) NULL,
    outcome VARCHAR(30) NOT NULL,
    details_json TEXT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_audit_tenant_created (tenant_id, created_at),
    INDEX idx_audit_resource (resource_type, resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE replay_jobs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(80) NOT NULL,
    requested_by VARCHAR(80) NOT NULL,
    status VARCHAR(30) NOT NULL,
    dry_run BIT NOT NULL,
    max_deliveries INT NOT NULL,
    processed_count INT NOT NULL DEFAULT 0,
    replayed_count INT NOT NULL DEFAULT 0,
    skipped_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    cancellation_requested BIT NOT NULL DEFAULT 0,
    approval_required BIT NOT NULL DEFAULT 1,
    approved_by VARCHAR(80) NULL,
    approved_at DATETIME(6) NULL,
    filter_json TEXT NULL,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    INDEX idx_replay_job_status_created (status, created_at),
    INDEX idx_replay_job_tenant_created (tenant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE scheduler_leases (
    lease_name VARCHAR(80) NOT NULL,
    owner_id VARCHAR(120) NOT NULL,
    locked_until DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (lease_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE outbox_messages
    ADD INDEX idx_outbox_lock_due (status, next_attempt_at, locked_until, id);

ALTER TABLE delivery_tasks
    ADD INDEX idx_delivery_endpoint_status (endpoint_id, status, next_attempt_at),
    ADD INDEX idx_delivery_fair_due (status, next_attempt_at, endpoint_id, id);
