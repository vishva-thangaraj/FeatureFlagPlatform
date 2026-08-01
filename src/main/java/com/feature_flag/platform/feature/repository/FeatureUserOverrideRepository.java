package com.feature_flag.platform.feature.repository;

import com.feature_flag.platform.feature.entity.FeatureUserOverride;
import org.springframework.data.jpa.repository.JpaRepository;
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
}