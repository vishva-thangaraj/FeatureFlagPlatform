package com.feature_flag.platform.feature.repository;

import com.feature_flag.platform.feature.entity.FeatureEnvStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FeatureEnvStatusRepository extends JpaRepository<FeatureEnvStatus, Long> {

    Optional<FeatureEnvStatus> findByFeatureIdAndEnvId(Long featureId, Long envId);

    List<FeatureEnvStatus> findAllByFeatureId(Long featureId);

    void deleteByFeatureIdAndEnvId(Long featureId, Long envId);
}