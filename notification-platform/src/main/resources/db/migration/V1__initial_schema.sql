CREATE TABLE application_clients (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(80) NOT NULL,
    app_id VARCHAR(80) NOT NULL,
    api_key VARCHAR(200) NOT NULL,
    role VARCHAR(30) NOT NULL,
    active BIT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_client_app_id UNIQUE (app_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE webhook_endpoints (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(80),
    name VARCHAR(80) NOT NULL,
    url VARCHAR(500) NOT NULL,
    secret VARCHAR(200) NOT NULL,
    event_types VARCHAR(500) NOT NULL,
    active BIT NOT NULL,
    max_attempts INT NOT NULL,
    rate_limit_per_minute INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_endpoint_tenant_active (tenant_id, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE event_records (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(80),
    app_id VARCHAR(80),
    event_id VARCHAR(80) NOT NULL,
    type VARCHAR(120) NOT NULL,
    trace_id VARCHAR(80),
    payload LONGTEXT NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_event_tenant_id UNIQUE (tenant_id, event_id),
    INDEX idx_event_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE delivery_tasks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id BIGINT NOT NULL,
    endpoint_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    attempt_count INT NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    locked_by VARCHAR(80),
    locked_until DATETIME(6),
    last_error VARCHAR(1000),
    last_status_code INT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    lock_version BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_delivery_event FOREIGN KEY (event_id) REFERENCES event_records(id),
    CONSTRAINT fk_delivery_endpoint FOREIGN KEY (endpoint_id) REFERENCES webhook_endpoints(id),
    INDEX idx_delivery_due (status, next_attempt_at),
    INDEX idx_delivery_event (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE delivery_attempts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    delivery_id BIGINT NOT NULL,
    attempt_no INT NOT NULL,
    success BIT NOT NULL,
    status_code INT,
    response_body VARCHAR(2000),
    error_message VARCHAR(1000),
    duration_ms BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_attempt_delivery FOREIGN KEY (delivery_id) REFERENCES delivery_tasks(id),
    INDEX idx_attempt_delivery_created (delivery_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
