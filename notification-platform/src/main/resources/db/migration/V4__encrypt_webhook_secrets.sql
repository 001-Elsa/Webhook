ALTER TABLE webhook_endpoints
    CHANGE COLUMN secret secret_encrypted VARCHAR(500) NOT NULL;
