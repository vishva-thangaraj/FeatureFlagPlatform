package com.feature_flag.platform.feature.repository;

import com.feature_flag.platform.feature.entity.FeatureRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FeatureRuleRepository extends JpaRepository<FeatureRule, Long> {

    List<FeatureRule> findAllByFeatureEnvStatusIdOrderByPriorityAsc(Long featureEnvStatusId);

    void deleteAllByFeatureEnvStatusId(Long featureEnvStatusId);
}