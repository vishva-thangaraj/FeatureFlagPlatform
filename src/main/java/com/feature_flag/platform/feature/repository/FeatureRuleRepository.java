package com.feature_flag.platform.feature.repository;

import com.feature_flag.platform.feature.entity.FeatureRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * Deepest level of the tenant-deletion cascade: every rule belonging to any of the tenant's
     * feature/env statuses, in one statement and without loading a row.
     *
     * <p>A status is the tenant's when either side of it is — the feature or the env. Both are always the
     * same tenant today, but the delete is written so a stray cross-tenant status could not survive as an
     * orphan holding an FK to a row that is about to disappear.
     *
     * <p>{@code flushAutomatically} pushes pending writes out before the statement so it sees them;
     * {@code clearAutomatically} drops the now-stale managed copies, which a bulk delete does not evict
     * on its own.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from FeatureRule r
            where r.featureEnvStatus.id in (
                select fes.id from FeatureEnvStatus fes
                where fes.feature.id in (select f.id from Feature f where f.tenant.id = :tenantId)
                   or fes.env.id in (select e.id from Env e where e.tenant.id = :tenantId))
            """)
    int deleteAllByTenantId(@Param("tenantId") Long tenantId);
}