ALTER TABLE application_clients
    CHANGE COLUMN api_key api_key_hash VARCHAR(200) NOT NULL;
