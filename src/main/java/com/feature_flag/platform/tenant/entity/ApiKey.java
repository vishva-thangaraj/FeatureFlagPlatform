package com.feature_flag.platform.tenant.entity;

import com.feature_flag.platform.common.entity.BaseEntity;
import com.feature_flag.platform.feature.entity.Env;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * An SDK credential. Scoped to one environment, not merely to a tenant: the config endpoint takes the
 * environment from the key, so a production key shipped inside a customer's binary cannot read the
 * staging configuration — which carries unreleased feature keys and targeting rules naming real users.
 */
@Entity
@Table(name = "api_keys", indexes = {
        @Index(name = "idx_api_keys_key_hash", columnList = "key_hash", unique = true),
        @Index(name = "idx_api_keys_tenant_id", columnList = "tenant_id"),
        @Index(name = "idx_api_keys_env_id", columnList = "env_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiKey extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "env_id", nullable = false, updatable = false)
    private Env env;

    @Column(nullable = false)
    private String name;

    /** SHA-256 of the key. The key itself is returned once, at creation, and never stored. */
    @Column(name = "key_hash", nullable = false, unique = true)
    private String keyHash;

    /** The key's leading, non-secret characters, so a key can be identified in the UI and in logs. */
    @Column(nullable = false, length = 32)
    private String prefix;

    private Instant expiresAt;

    /** Set instead of deleting the row, so a revoked key stays auditable. */
    private Instant revokedAt;

    /** Written at most once every few minutes per key; see ApiKeyAuthenticationFilter. */
    private Instant lastUsedAt;
}
