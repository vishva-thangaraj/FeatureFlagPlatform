package com.feature_flag.platform.feature.repository;

import com.feature_flag.platform.feature.entity.Feature;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FeatureRepository extends JpaRepository<Feature, Long> {

    Optional<Feature> findByKeyAndTenantId(String key, Long tenantId);

    List<Feature> findAllByTenantId(Long tenantId);

    boolean existsByKeyAndTenantId(String key, Long tenantId);

    /**
     * Tenant-deletion cascade. Written as a bulk statement rather than the derived
     * {@code deleteAllByTenantId}, which would fetch every feature of the tenant and delete them one by
     * one — a tenant with thousands of flags must not be pulled into memory to be dropped.
     *
     * <p>Nothing cascades from {@code Feature}, so {@code feature_env_status} must already be gone.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Feature f where f.tenant.id = :tenantId")
    int deleteAllByTenantId(@Param("tenantId") Long tenantId);
}