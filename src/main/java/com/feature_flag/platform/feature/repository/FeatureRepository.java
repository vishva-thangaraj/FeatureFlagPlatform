package com.feature_flag.platform.feature.repository;

import com.feature_flag.platform.feature.entity.Feature;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FeatureRepository extends JpaRepository<Feature, Long> {

    Optional<Feature> findByKeyAndTenantId(String key, Long tenantId);

    List<Feature> findAllByTenantId(Long tenantId);

    boolean existsByKeyAndTenantId(String key, Long tenantId);
}