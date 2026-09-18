package com.feature_flag.platform.feature.repository;

import com.feature_flag.platform.feature.entity.FeatureEnvStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FeatureEnvStatusRepository extends JpaRepository<FeatureEnvStatus, Long> {

    Optional<FeatureEnvStatus> findByFeatureIdAndEnvId(Long featureId, Long envId);

    List<FeatureEnvStatus> findAllByFeatureId(Long featureId);

    /** Every status row in one environment — the rows that must go before the env row itself can. */
    List<FeatureEnvStatus> findAllByEnvId(Long envId);

    void deleteByFeatureIdAndEnvId(Long featureId, Long envId);

    // ---- Client config lookups, keyed by feature key + env name within a tenant (D10) ----
    //
    // Both of these JOIN FETCH feature and env on purpose. The config payload names the feature key
    // and the env name for every row, so a derived query here costs one extra SELECT per flag the
    // moment those LAZY associations are dereferenced.

    @Query("""
    SELECT fes FROM FeatureEnvStatus fes
    JOIN FETCH fes.feature f
    JOIN FETCH fes.env e
    WHERE f.key = :featureKey
      AND f.tenant.id = :tenantId
      AND e.name = :envName
      AND e.tenant.id = :tenantId
    """)
    Optional<FeatureEnvStatus> findConfig(@Param("featureKey") String featureKey,
                                          @Param("tenantId") Long tenantId,
                                          @Param("envName") String envName);

    @Query("""
    SELECT fes FROM FeatureEnvStatus fes
    JOIN FETCH fes.feature f
    JOIN FETCH fes.env e
    WHERE f.tenant.id = :tenantId
      AND e.name = :envName
      AND e.tenant.id = :tenantId
    """)
    List<FeatureEnvStatus> findAllConfigs(@Param("tenantId") Long tenantId,
                                          @Param("envName") String envName);

    /**
     * Tenant-deletion cascade: every status joining one of the tenant's features to one of its envs. Must
     * run after the rules and overrides that reference these rows, and before the features and envs they
     * reference.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from FeatureEnvStatus fes
            where fes.feature.id in (select f.id from Feature f where f.tenant.id = :tenantId)
               or fes.env.id in (select e.id from Env e where e.tenant.id = :tenantId)
            """)
    int deleteAllByTenantId(@Param("tenantId") Long tenantId);
}