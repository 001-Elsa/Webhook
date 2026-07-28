-- RETRY messages are now held in MySQL until next_attempt_at and then published
-- to the normal delivery queue. Existing rows remain valid and need no rewrite.
ALTER TABLE outbox_messages
    ADD COLUMN scheduled_source VARCHAR(30) NOT NULL DEFAULT 'IMMEDIATE';
