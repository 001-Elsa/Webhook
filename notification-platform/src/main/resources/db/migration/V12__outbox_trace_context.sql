-- Preserve the API server span as the parent for asynchronous publisher work.
ALTER TABLE outbox_messages
    ADD COLUMN trace_parent VARCHAR(55) NULL;
