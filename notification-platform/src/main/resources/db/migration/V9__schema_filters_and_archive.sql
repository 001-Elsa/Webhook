ALTER TABLE event_records
    ADD COLUMN schema_version VARCHAR(40) NOT NULL DEFAULT '1';

ALTER TABLE webhook_endpoints
    ADD COLUMN filter_expression VARCHAR(500) NULL;

ALTER TABLE outbox_messages
    ADD COLUMN logical_partition SMALLINT NOT NULL DEFAULT 0,
    ADD INDEX idx_outbox_partition_due (logical_partition, status, next_attempt_at, id);

UPDATE outbox_messages SET logical_partition = MOD(delivery_id, 16);

CREATE TABLE delivery_attempt_archive (
    id BIGINT NOT NULL,
    delivery_id BIGINT NOT NULL,
    attempt_no INT NOT NULL,
    success BIT NOT NULL,
    status_code INT NULL,
    response_body VARCHAR(2000) NULL,
    error_message VARCHAR(1000) NULL,
    duration_ms BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    archived_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_attempt_archive_delivery_created (delivery_id, created_at),
    INDEX idx_attempt_archive_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
