-- Users
CREATE TABLE users (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email       VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_users_email UNIQUE (email)
);

CREATE INDEX idx_users_email ON users (email);
CREATE INDEX idx_users_status ON users (status);

-- Tenants
CREATE TABLE tenants (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(255) NOT NULL,
    plan       VARCHAR(20)  NOT NULL DEFAULT 'FREE',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_tenants_name ON tenants (name);

-- Roles
CREATE TABLE roles (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_roles_name UNIQUE (name)
);

-- Seed default roles
INSERT INTO roles (id, name) VALUES
    (gen_random_uuid(), 'OWNER'),
    (gen_random_uuid(), 'ADMIN'),
    (gen_random_uuid(), 'MEMBER'),
    (gen_random_uuid(), 'VIEWER');

-- Memberships
CREATE TABLE memberships (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id   UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    tenant_id UUID        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    status    VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_memberships_user_tenant UNIQUE (user_id, tenant_id)
);

CREATE INDEX idx_memberships_user_id   ON memberships (user_id);
CREATE INDEX idx_memberships_tenant_id ON memberships (tenant_id);
CREATE INDEX idx_memberships_status    ON memberships (status);

-- Membership ↔ Role join table
CREATE TABLE membership_roles (
    membership_id UUID NOT NULL REFERENCES memberships (id) ON DELETE CASCADE,
    role_id       UUID NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    CONSTRAINT uk_membership_roles PRIMARY KEY (membership_id, role_id)
);

-- API Keys
CREATE TABLE api_keys (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name      VARCHAR(255) NOT NULL,
    key_hash  VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ,
    CONSTRAINT uk_api_keys_key_hash UNIQUE (key_hash)
);

CREATE INDEX idx_api_keys_key_hash  ON api_keys (key_hash);
CREATE INDEX idx_api_keys_tenant_id ON api_keys (tenant_id);