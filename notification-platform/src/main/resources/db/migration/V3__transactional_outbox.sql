CREATE TABLE outbox_messages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    delivery_id BIGINT NOT NULL,
    message_type VARCHAR(30) NOT NULL,
    attempt_no INT NOT NULL,
    status VARCHAR(30) NOT NULL,
    publish_attempts INT NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    locked_by VARCHAR(80),
    locked_until DATETIME(6),
    last_error VARCHAR(1000),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_outbox_delivery FOREIGN KEY (delivery_id) REFERENCES delivery_tasks(id),
    INDEX idx_outbox_due (status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
