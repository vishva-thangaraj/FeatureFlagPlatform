package com.feature_flag.platform.tenant.repository;

import com.feature_flag.platform.tenant.dto.ApiKeyIdentity;
import com.feature_flag.platform.tenant.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    /**
     * The only lookup by hash. There is deliberately no plain {@code findByKeyHash}: liveness is part of
     * the query rather than something each caller remembers to check afterwards, so a revoked or expired
     * key cannot authenticate through a call site that forgot.
     *
     * <p>Both conditions are exhaustive over two-state columns — a key is revoked or it is not — unlike
     * the token status enum, where a denylist left any new value implicitly allowed.
     */
    @Query("""
            select new com.feature_flag.platform.tenant.dto.ApiKeyIdentity(
                k.id, k.tenant.id, k.env.id, k.env.name, k.prefix)
            from ApiKey k
            where k.keyHash = :keyHash
              and k.revokedAt is null
              and (k.expiresAt is null or k.expiresAt > :now)
            """)
    Optional<ApiKeyIdentity> findActiveByKeyHash(@Param("keyHash") String keyHash, @Param("now") Instant now);

    /**
     * Tenant-scoped by id, so revoking cannot be aimed at another tenant's key. The check belongs in the
     * query, not in a service that compares ids after loading the row.
     */
    Optional<ApiKey> findByIdAndTenantId(Long id, Long tenantId);

    List<ApiKey> findByTenantId(Long tenantId);

    List<ApiKey> findByTenantIdAndEnvId(Long tenantId, Long envId);

    /** Listing shows each key's environment, so fetch it rather than lazy-loading one row at a time. */
    @Query("""
            select k from ApiKey k join fetch k.env
            where k.tenant.id = :tenantId
            order by k.createdAt desc
            """)
    List<ApiKey> findByTenantIdWithEnv(@Param("tenantId") Long tenantId);

    /** Best-effort usage stamp. Coalesced by the caller; never on the request's critical path. */
    @Modifying
    @Transactional
    @Query("update ApiKey k set k.lastUsedAt = :now where k.id = :id")
    int touchLastUsedAt(@Param("id") Long id, @Param("now") Instant now);

    /**
     * Tenant-deletion cascade. API keys are leaves; deleting the rows is what actually revokes SDK
     * access, so they must not outlive the tenant they authenticate against.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ApiKey k where k.tenant.id = :tenantId")
    int deleteAllByTenantId(@Param("tenantId") Long tenantId);
}
