package com.feature_flag.platform.feature.repository;

import com.feature_flag.platform.feature.entity.FeatureUserOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface FeatureUserOverrideRepository extends JpaRepository<FeatureUserOverride, Long> {

    Optional<FeatureUserOverride> findByFeatureEnvStatusIdAndExternalUserId(Long featureEnvStatusId, String externalUserId);

    List<FeatureUserOverride> findAllByFeatureEnvStatusId(Long featureEnvStatusId);

    List<FeatureUserOverride> findAllByFeatureEnvStatusIdIn(Collection<Long> featureEnvStatusIds);

    void deleteByFeatureEnvStatusIdAndExternalUserId(Long featureEnvStatusId, String externalUserId);

    void deleteAllByFeatureEnvStatusId(Long featureEnvStatusId);

    /**
     * Tenant-deletion cascade, same level as {@code FeatureRuleRepository.deleteAllByTenantId}: every
     * override hanging off any of the tenant's feature/env statuses, in one statement.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from FeatureUserOverride o
            where o.featureEnvStatus.id in (
                select fes.id from FeatureEnvStatus fes
                where fes.feature.id in (select f.id from Feature f where f.tenant.id = :tenantId)
                   or fes.env.id in (select e.id from Env e where e.tenant.id = :tenantId))
            """)
    int deleteAllByTenantId(@Param("tenantId") Long tenantId);
}