package com.feature_flag.platform.tenant.repository;

import com.feature_flag.platform.common.security.ApiKeyGenerator;
import com.feature_flag.platform.common.security.TokenHashUtil;
import com.feature_flag.platform.feature.entity.Env;
import com.feature_flag.platform.tenant.dto.ApiKeyIdentity;
import com.feature_flag.platform.tenant.entity.ApiKey;
import com.feature_flag.platform.tenant.entity.Tenant;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which keys may authenticate. The liveness rules live in the query rather than in the filter, so this
 * is where they are pinned: a call site cannot opt out of a condition it never had to remember.
 */
@DataJpaTest
@ActiveProfiles("test")
class ApiKeyRepositoryTest {

    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private EntityManager entityManager;

    private Tenant tenant;
    private Tenant otherTenant;
    private Env production;

    @BeforeEach
    void setUp() {
        tenant = persist(Tenant.builder().name("Acme").build());
        otherTenant = persist(Tenant.builder().name("Globex").build());
        production = persist(Env.builder().name("production").tenant(tenant).build());
    }

    @Test
    @DisplayName("a live key resolves to its tenant and its environment")
    void liveKeyResolves() {
        String plaintext = store("live", null, null);

        Optional<ApiKeyIdentity> found = lookup(plaintext);

        assertThat(found).isPresent();
        assertThat(found.get().tenantId()).isEqualTo(tenant.getId());
        assertThat(found.get().envId()).isEqualTo(production.getId());
        assertThat(found.get().envName()).isEqualTo("production");
    }

    @Test
    @DisplayName("a revoked key does not resolve, and the row survives for the audit trail")
    void revokedKeyDoesNotResolve() {
        String plaintext = store("revoked", null, Instant.now().minus(1, ChronoUnit.MINUTES));

        assertThat(lookup(plaintext)).isEmpty();
        assertThat(apiKeyRepository.findByTenantId(tenant.getId())).hasSize(1);
    }

    @Test
    @DisplayName("an expired key does not resolve; one expiring later still does")
    void expiryIsEnforced() {
        String expired = store("expired", Instant.now().minus(1, ChronoUnit.HOURS), null);
        String future = store("future", Instant.now().plus(1, ChronoUnit.HOURS), null);

        assertThat(lookup(expired)).isEmpty();
        assertThat(lookup(future)).isPresent();
    }

    @Test
    @DisplayName("an unknown hash resolves to nothing")
    void unknownKeyDoesNotResolve() {
        store("live", null, null);

        assertThat(lookup(ApiKeyGenerator.generate().plaintext())).isEmpty();
    }

    @Test
    @DisplayName("a key id belonging to another tenant is not found, so revoke cannot be aimed across tenants")
    void lookupByIdIsTenantScoped() {
        String plaintext = store("live", null, null);
        Long id = lookup(plaintext).orElseThrow().apiKeyId();

        assertThat(apiKeyRepository.findByIdAndTenantId(id, tenant.getId())).isPresent();
        assertThat(apiKeyRepository.findByIdAndTenantId(id, otherTenant.getId())).isEmpty();
    }

    private Optional<ApiKeyIdentity> lookup(String plaintext) {
        return apiKeyRepository.findActiveByKeyHash(TokenHashUtil.sha256(plaintext), Instant.now());
    }

    private String store(String name, Instant expiresAt, Instant revokedAt) {
        ApiKeyGenerator.GeneratedKey generated = ApiKeyGenerator.generate();
        apiKeyRepository.saveAndFlush(ApiKey.builder()
                .tenant(tenant)
                .env(production)
                .name(name)
                .keyHash(generated.keyHash())
                .prefix(generated.prefix())
                .expiresAt(expiresAt)
                .revokedAt(revokedAt)
                .build());
        return generated.plaintext();
    }

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        entityManager.flush();
        return entity;
    }
}
