package com.feature_flag.platform.auth.repository;

import com.feature_flag.platform.auth.entity.RefreshToken;
import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.auth.enums.UserStatus;
import com.feature_flag.platform.tenant.entity.Tenant;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tenant-deletion cleanup of refresh tokens, on real MySQL. H2 in MySQL mode accepts an UPDATE whose
 * subquery reads the updated table; MySQL rejects it (error 1093), which the H2 suite could not catch.
 * Skipped when no Docker daemon is reachable.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class RefreshTokenRepositoryMySqlTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQLDialect");
        // The container is gone by the time the cached context closes, so there is nothing to drop.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("a rotation chain A→B→C and a pointer into it from another tenant are detached, then deleted")
    void detachThenDeleteRemovesTheChainAndReleasesOutsidePointers() {
        User owner = persist(User.builder()
                .email("owner@test.com").firstName("Owner").passwordHash("hash")
                .status(UserStatus.ACTIVE).build());
        Tenant doomed = persist(Tenant.builder().name("Doomed").build());
        Tenant neighbour = persist(Tenant.builder().name("Neighbour").build());

        RefreshToken a = token(owner, doomed, "a");
        RefreshToken b = token(owner, doomed, "b");
        RefreshToken c = token(owner, doomed, "c");
        a.setReplacedBy(b);
        b.setReplacedBy(c);
        RefreshToken intoDoomed = token(owner, neighbour, "into-doomed");
        intoDoomed.setReplacedBy(a);
        RefreshToken y = token(owner, neighbour, "y");
        RefreshToken z = token(owner, neighbour, "z");
        y.setReplacedBy(z);
        entityManager.flush();
        entityManager.clear();

        int detached = refreshTokenRepository.detachReplacementChainsByTenantId(doomed.getId());
        int deleted = refreshTokenRepository.deleteAllByTenantId(doomed.getId());
        entityManager.flush();

        assertThat(detached).isEqualTo(3);
        assertThat(deleted).isEqualTo(3);
        assertThat(countByTenant(doomed.getId())).isZero();
        assertThat(countByTenant(neighbour.getId())).isEqualTo(3);
        assertThat(replacedBy(intoDoomed.getId())).isNull();
        assertThat(replacedBy(y.getId())).isEqualTo(z.getId());
    }

    @Test
    @DisplayName("a tenant whose tokens were never rotated detaches nothing")
    void detachWithoutRotationIsANoOp() {
        User owner = persist(User.builder()
                .email("owner@test.com").firstName("Owner").passwordHash("hash")
                .status(UserStatus.ACTIVE).build());
        Tenant tenant = persist(Tenant.builder().name("Fresh").build());
        token(owner, tenant, "only");
        entityManager.flush();

        assertThat(refreshTokenRepository.detachReplacementChainsByTenantId(tenant.getId())).isZero();
        assertThat(refreshTokenRepository.deleteAllByTenantId(tenant.getId())).isEqualTo(1);
    }

    private RefreshToken token(User user, Tenant tenant, String hash) {
        return persist(RefreshToken.builder()
                .user(user).tenant(tenant).tokenHash(hash)
                .expiresAt(Instant.now().plusSeconds(3600)).build());
    }

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        return entity;
    }

    private long countByTenant(Long tenantId) {
        return ((Number) entityManager
                .createNativeQuery("select count(*) from refresh_tokens where tenant_id = :id")
                .setParameter("id", tenantId)
                .getSingleResult()).longValue();
    }

    private Long replacedBy(Long tokenId) {
        Number id = (Number) entityManager
                .createNativeQuery("select replaced_by from refresh_tokens where id = :id")
                .setParameter("id", tokenId)
                .getSingleResult();
        return id == null ? null : id.longValue();
    }
}
