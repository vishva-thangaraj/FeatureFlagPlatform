CREATE TABLE user_invitations (
    id         BIGINT PRIMARY KEY,
    email      VARCHAR(255) NOT NULL,
    token_hash VARCHAR(255) NOT NULL,
    role       VARCHAR(20)  NOT NULL,
    tenant_id  BIGINT       NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    invited_by BIGINT       REFERENCES users (id) ON DELETE SET NULL,
    status     VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    expires_at TIMESTAMPTZ  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_user_invitations_token_hash UNIQUE (token_hash)
);

-- Partial unique index: only one PENDING invitation per email+tenant
CREATE UNIQUE INDEX uk_user_invitations_email_tenant_pending
    ON user_invitations (email, tenant_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_user_invitations_email     ON user_invitations (email);
CREATE INDEX idx_user_invitations_tenant_id ON user_invitations (tenant_id);
CREATE INDEX idx_user_invitations_token_hash ON user_invitations (token_hash);
CREATE INDEX idx_user_invitations_status    ON user_invitations (status);