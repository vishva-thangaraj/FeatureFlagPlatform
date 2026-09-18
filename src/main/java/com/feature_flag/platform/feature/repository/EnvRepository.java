package com.feature_flag.platform.feature.repository;

import com.feature_flag.platform.feature.entity.Env;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EnvRepository extends JpaRepository<Env, Long> {

    Optional<Env> findByNameAndTenantId(String name, Long tenantId);

    List<Env> findAllByTenantId(Long tenantId);

    boolean existsByNameAndTenantId(String name, Long tenantId);

    /**
     * Tenant-deletion cascade; bulk for the same reason as {@code FeatureRepository.deleteAllByTenantId}.
     * {@code feature_env_status} also points here, so it must already be gone.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Env e where e.tenant.id = :tenantId")
    int deleteAllByTenantId(@Param("tenantId") Long tenantId);
}