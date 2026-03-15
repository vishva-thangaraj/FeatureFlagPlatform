-- Migrate all primary keys and foreign keys from UUID to BIGINT (TSID)
-- This is a dev-only destructive migration — drop and recreate all tables

DROP TABLE IF EXISTS refresh_tokens CASCADE;
DROP TABLE IF EXISTS membership_roles CASCADE;
DROP TABLE IF EXISTS api_keys CASCADE;
DROP TABLE IF EXISTS memberships CASCADE;
DROP TABLE IF EXISTS roles CASCADE;
DROP TABLE IF EXISTS tenants CASCADE;
DROP TABLE IF EXISTS users CASCADE;

-- Users
CREATE TABLE users (
    id            BIGINT PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    first_name    VARCHAR(255) NOT NULL,
    last_name     VARCHAR(255),
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_users_email UNIQUE (email)
);

CREATE INDEX idx_users_email      ON users (email);
CREATE INDEX idx_users_status     ON users (status);
CREATE INDEX idx_users_first_name ON users (first_name);

-- Tenants
CREATE TABLE tenants (
    id         BIGINT PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    plan       VARCHAR(20)  NOT NULL DEFAULT 'FREE',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_tenants_name ON tenants (name);

-- Roles
CREATE TABLE roles (
    id         BIGINT PRIMARY KEY,
    name       VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_roles_name UNIQUE (name)
);

-- Memberships
CREATE TABLE memberships (
    id         BIGINT PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    tenant_id  BIGINT      NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    status     VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_memberships_user_tenant UNIQUE (user_id, tenant_id)
);

CREATE INDEX idx_memberships_user_id   ON memberships (user_id);
CREATE INDEX idx_memberships_tenant_id ON memberships (tenant_id);
CREATE INDEX idx_memberships_status    ON memberships (status);

-- Membership <-> Role join table
CREATE TABLE membership_roles (
    membership_id BIGINT NOT NULL REFERENCES memberships (id) ON DELETE CASCADE,
    role_id       BIGINT NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    CONSTRAINT uk_membership_roles PRIMARY KEY (membership_id, role_id)
);

-- API Keys
CREATE TABLE api_keys (
    id         BIGINT PRIMARY KEY,
    tenant_id  BIGINT       NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name       VARCHAR(255) NOT NULL,
    key_hash   VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ,
    CONSTRAINT uk_api_keys_key_hash UNIQUE (key_hash)
);

CREATE INDEX idx_api_keys_key_hash  ON api_keys (key_hash);
CREATE INDEX idx_api_keys_tenant_id ON api_keys (tenant_id);

-- Refresh Tokens
CREATE TABLE refresh_tokens (
    id          BIGINT PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    tenant_id   BIGINT       REFERENCES tenants (id) ON DELETE CASCADE,
    token_hash  VARCHAR(255) NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    revoked     BOOLEAN      NOT NULL DEFAULT FALSE,
    replaced_by BIGINT       REFERENCES refresh_tokens (id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_refresh_tokens_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_user_id    ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_token_hash ON refresh_tokens (token_hash);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);
