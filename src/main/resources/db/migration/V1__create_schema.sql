-- ============================================================================
-- V1: Complete schema for Feature Flag Platform (Oracle Database)
-- Note: Oracle auto-creates indexes for UNIQUE and PRIMARY KEY constraints,
--       so we only create indexes for non-unique columns.
-- ============================================================================

-- Drop tables if they exist from a previous partial run
BEGIN
    FOR t IN (SELECT table_name FROM user_tables WHERE table_name IN (
        'USER_INVITATIONS','REFRESH_TOKENS','API_KEYS','MEMBERSHIP_ROLES',
        'MEMBERSHIPS','ROLES','TENANTS','USERS',
        'FLYWAY_SCHEMA_HISTORY','FLYWAY_SCHEMA_HISTORY_V2'
    )) LOOP
        EXECUTE IMMEDIATE 'DROP TABLE "' || t.table_name || '" CASCADE CONSTRAINTS PURGE';
    END LOOP;
END;
/

-- Users
CREATE TABLE users (
    id            NUMBER(19) PRIMARY KEY,
    email         VARCHAR2(255) NOT NULL,
    password_hash VARCHAR2(255) NOT NULL,
    first_name    VARCHAR2(255) NOT NULL,
    last_name     VARCHAR2(255),
    status        VARCHAR2(20) DEFAULT 'ACTIVE' NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP,
    created_by    NUMBER(19),
    updated_by    NUMBER(19),
    CONSTRAINT uk_users_email UNIQUE (email)
);

CREATE INDEX idx_users_status ON users (status);

-- Tenants
CREATE TABLE tenants (
    id         NUMBER(19) PRIMARY KEY,
    name       VARCHAR2(255) NOT NULL,
    plan       VARCHAR2(20) DEFAULT 'FREE' NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP,
    created_by NUMBER(19),
    updated_by NUMBER(19)
);

CREATE INDEX idx_tenants_name ON tenants (name);

-- Roles
CREATE TABLE roles (
    id         NUMBER(19) PRIMARY KEY,
    name       VARCHAR2(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP,
    created_by NUMBER(19),
    updated_by NUMBER(19),
    CONSTRAINT uk_roles_name UNIQUE (name)
);

-- Memberships
CREATE TABLE memberships (
    id         NUMBER(19) PRIMARY KEY,
    user_id    NUMBER(19) NOT NULL,
    tenant_id  NUMBER(19) NOT NULL,
    status     VARCHAR2(20) DEFAULT 'ACTIVE' NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP,
    created_by NUMBER(19),
    updated_by NUMBER(19),
    CONSTRAINT uk_memberships_user_tenant UNIQUE (user_id, tenant_id),
    CONSTRAINT fk_memberships_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_memberships_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE
);

CREATE INDEX idx_memberships_tenant_id ON memberships (tenant_id);
CREATE INDEX idx_memberships_status ON memberships (status);

-- Membership <-> Role join table
CREATE TABLE membership_roles (
    membership_id NUMBER(19) NOT NULL,
    role_id       NUMBER(19) NOT NULL,
    CONSTRAINT uk_membership_roles PRIMARY KEY (membership_id, role_id),
    CONSTRAINT fk_mr_membership FOREIGN KEY (membership_id) REFERENCES memberships (id) ON DELETE CASCADE,
    CONSTRAINT fk_mr_role FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE CASCADE
);

-- API Keys
CREATE TABLE api_keys (
    id         NUMBER(19) PRIMARY KEY,
    tenant_id  NUMBER(19) NOT NULL,
    name       VARCHAR2(255) NOT NULL,
    key_hash   VARCHAR2(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP,
    created_by NUMBER(19),
    updated_by NUMBER(19),
    expires_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_api_keys_key_hash UNIQUE (key_hash),
    CONSTRAINT fk_api_keys_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE
);

CREATE INDEX idx_api_keys_tenant_id ON api_keys (tenant_id);

-- Refresh Tokens
CREATE TABLE refresh_tokens (
    id          NUMBER(19) PRIMARY KEY,
    user_id     NUMBER(19) NOT NULL,
    tenant_id   NUMBER(19),
    token_hash  VARCHAR2(255) NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked     NUMBER(1) DEFAULT 0 NOT NULL,
    replaced_by NUMBER(19),
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP,
    created_by  NUMBER(19),
    updated_by  NUMBER(19),
    CONSTRAINT uk_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_refresh_tokens_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE,
    CONSTRAINT fk_refresh_tokens_replaced FOREIGN KEY (replaced_by) REFERENCES refresh_tokens (id)
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);

-- User Invitations
CREATE TABLE user_invitations (
    id         NUMBER(19) PRIMARY KEY,
    email      VARCHAR2(255) NOT NULL,
    token_hash VARCHAR2(255) NOT NULL,
    role       VARCHAR2(20) NOT NULL,
    tenant_id  NUMBER(19) NOT NULL,
    invited_by NUMBER(19),
    status     VARCHAR2(20) DEFAULT 'PENDING' NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP,
    created_by NUMBER(19),
    updated_by NUMBER(19),
    CONSTRAINT uk_user_invitations_token UNIQUE (token_hash),
    CONSTRAINT fk_user_inv_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_inv_inviter FOREIGN KEY (invited_by) REFERENCES users (id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX uk_user_inv_email_tenant_pending
    ON user_invitations (
        CASE WHEN status = 'PENDING' THEN email END,
        CASE WHEN status = 'PENDING' THEN tenant_id END
    );

CREATE INDEX idx_user_invitations_email ON user_invitations (email);
CREATE INDEX idx_user_invitations_tenant ON user_invitations (tenant_id);
CREATE INDEX idx_user_invitations_status ON user_invitations (status);
