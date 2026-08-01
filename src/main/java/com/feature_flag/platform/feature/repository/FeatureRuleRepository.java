package com.feature_flag.platform.feature.repository;

import com.feature_flag.platform.feature.entity.FeatureRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface FeatureRuleRepository extends JpaRepository<FeatureRule, Long> {

    List<FeatureRule> findAllByFeatureEnvStatusIdOrderByPriorityAsc(Long featureEnvStatusId);

    /** Batch fetch across many FeatureEnvStatus rows, keeping the bulk config endpoint at O(1) queries. */
    List<FeatureRule> findAllByFeatureEnvStatusIdInOrderByPriorityAsc(Collection<Long> featureEnvStatusIds);

    @Query("select distinct r.attribute from FeatureRule r " +
            "where r.featureEnvStatus.feature.tenant.id = :tenantId order by r.attribute")
    List<String> findDistinctAttributesByTenantId(@Param("tenantId") Long tenantId);

    void deleteAllByFeatureEnvStatusId(Long featureEnvStatusId);
}