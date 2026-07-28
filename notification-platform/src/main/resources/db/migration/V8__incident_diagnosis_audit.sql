CREATE TABLE incident_diagnoses (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(80) NOT NULL,
    delivery_id BIGINT NOT NULL,
    category VARCHAR(80) NOT NULL,
    confidence DOUBLE NOT NULL,
    summary VARCHAR(1000) NOT NULL,
    evidence_json TEXT NOT NULL,
    actions_json TEXT NOT NULL,
    replay_recommended BIT NOT NULL,
    analyzer VARCHAR(80) NOT NULL,
    model VARCHAR(120) NULL,
    prompt_version VARCHAR(80) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_diagnosis_delivery FOREIGN KEY (delivery_id) REFERENCES delivery_tasks(id),
    INDEX idx_diagnosis_tenant_created (tenant_id, created_at),
    INDEX idx_diagnosis_delivery_created (delivery_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
