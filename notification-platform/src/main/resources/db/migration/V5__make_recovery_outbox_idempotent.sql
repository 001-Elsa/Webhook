-- MySQL has no partial unique index. NULL values remain distinct, so this only
-- deduplicates RECOVERY messages and cannot invalidate historical non-recovery rows.
ALTER TABLE outbox_messages
    ADD COLUMN recovery_attempt_no INT GENERATED ALWAYS AS
        (CASE WHEN message_type = 'RECOVERY' THEN attempt_no ELSE NULL END) STORED,
    ADD CONSTRAINT uk_outbox_recovery_delivery_attempt UNIQUE (delivery_id, recovery_attempt_no);
