package com.feature_flag.platform.feature.repository;

import com.feature_flag.platform.feature.entity.FeatureEnvStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FeatureEnvStatusRepository extends JpaRepository<FeatureEnvStatus, Long> {

    Optional<FeatureEnvStatus> findByFeatureIdAndEnvId(Long featureId, Long envId);

    List<FeatureEnvStatus> findAllByFeatureId(Long featureId);

    void deleteByFeatureIdAndEnvId(Long featureId, Long envId);

    // ---- Client config lookups, keyed by feature key + env name within a tenant (D10) ----

    Optional<FeatureEnvStatus> findByFeature_KeyAndFeature_Tenant_IdAndEnv_Name(
            String featureKey, Long tenantId, String envName);

    List<FeatureEnvStatus> findAllByFeature_Tenant_IdAndEnv_Name(Long tenantId, String envName);

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
}