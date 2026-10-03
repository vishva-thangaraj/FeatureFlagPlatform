-- Scopes API keys to an environment and makes revocation auditable.
--
-- Apply BEFORE deploying the image that expects it. Production runs
-- spring.jpa.hibernate.ddl-auto=validate, so the application will refuse to start against the old
-- shape. That is the correct failure, and still a failed deploy — order matters.
--
--   mysql -h <rds-endpoint> -u admin -p featureflag < 001-api-keys-env-scope.sql
--
-- Generated from the entity mapping (jakarta.persistence.schema-generation against MySQLDialect),
-- not hand-written, so it matches what `validate` will check.

-- Guard: the NOT NULL columns below can only be added to an empty table, and api_keys has never
-- been written to. A non-zero count here means stop and backfill instead.
SELECT COUNT(*) AS existing_api_keys FROM api_keys;

ALTER TABLE api_keys
    ADD COLUMN env_id       BIGINT      NOT NULL,
    ADD COLUMN prefix       VARCHAR(32) NOT NULL,
    ADD COLUMN revoked_at   DATETIME(6) NULL,
    ADD COLUMN last_used_at DATETIME(6) NULL;

CREATE INDEX idx_api_keys_env_id ON api_keys (env_id);

ALTER TABLE api_keys
    ADD CONSTRAINT fk_api_keys_env FOREIGN KEY (env_id) REFERENCES env (id);

-- Verify: the application validates against exactly these columns.
SHOW CREATE TABLE api_keys;
