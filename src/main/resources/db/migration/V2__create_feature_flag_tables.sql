-- ============================================================================
-- V2: Feature flag tables
-- ============================================================================

-- Feature
CREATE TABLE feature (
    id            NUMBER(19) PRIMARY KEY,
    key           VARCHAR2(50) NOT NULL,
    description   VARCHAR2(255),
    tenant_id     NUMBER(19) NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP,
    created_by    NUMBER(19),
    updated_by    NUMBER(19),
    CONSTRAINT uk_feature_key_tenant UNIQUE (key, tenant_id),
    CONSTRAINT fk_feature_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE
);

CREATE INDEX idx_feature_key ON feature (key);
CREATE INDEX idx_feature_tenant_id ON feature (tenant_id);

-- Environment
CREATE TABLE env (
    id            NUMBER(19) PRIMARY KEY,
    name          VARCHAR2(255) NOT NULL,
    description   VARCHAR2(255),
    tenant_id     NUMBER(19) NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP,
    created_by    NUMBER(19),
    updated_by    NUMBER(19),
    CONSTRAINT uk_env_name_tenant UNIQUE (name, tenant_id),
    CONSTRAINT fk_env_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE
);

CREATE INDEX idx_env_name ON env (name);
CREATE INDEX idx_env_tenant_id ON env (tenant_id);

-- Feature-Environment Status
CREATE TABLE feature_env_status (
    id            NUMBER(19) PRIMARY KEY,
    feature_id    NUMBER(19) NOT NULL,
    env_id        NUMBER(19) NOT NULL,
    enabled       NUMBER(1) DEFAULT 0 NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP,
    created_by    NUMBER(19),
    updated_by    NUMBER(19),
    CONSTRAINT uk_fes_feature_env UNIQUE (feature_id, env_id),
    CONSTRAINT fk_fes_feature FOREIGN KEY (feature_id) REFERENCES feature (id) ON DELETE CASCADE,
    CONSTRAINT fk_fes_env FOREIGN KEY (env_id) REFERENCES env (id) ON DELETE CASCADE
);

CREATE INDEX idx_fes_feature_id ON feature_env_status (feature_id);
CREATE INDEX idx_fes_env_id ON feature_env_status (env_id);

-- Feature User Override (external_user_id is the tenant's end-user, no FK to users)
CREATE TABLE feature_user_override (
    id                     NUMBER(19) PRIMARY KEY,
    feature_env_status_id  NUMBER(19) NOT NULL,
    external_user_id       VARCHAR2(255) NOT NULL,
    enabled                NUMBER(1) NOT NULL,
    created_at             TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at             TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP,
    created_by             NUMBER(19),
    updated_by             NUMBER(19),
    CONSTRAINT uk_fuo_fes_user UNIQUE (feature_env_status_id, external_user_id),
    CONSTRAINT fk_fuo_fes FOREIGN KEY (feature_env_status_id) REFERENCES feature_env_status (id) ON DELETE CASCADE
);

CREATE INDEX idx_fuo_fes_id ON feature_user_override (feature_env_status_id);
CREATE INDEX idx_fuo_external_user_id ON feature_user_override (external_user_id);