package com.feature_flag.platform.tenant.repository;

import com.feature_flag.platform.tenant.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    Optional<ApiKey> findByKeyHash(String keyHash);

    List<ApiKey> findByTenantId(Long tenantId);

    /**
     * Tenant-deletion cascade. API keys are leaves; deleting the rows is what actually revokes SDK
     * access, so they must not outlive the tenant they authenticate against.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ApiKey k where k.tenant.id = :tenantId")
    int deleteAllByTenantId(@Param("tenantId") Long tenantId);
}
