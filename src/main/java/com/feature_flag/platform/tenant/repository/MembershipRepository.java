package com.feature_flag.platform.tenant.repository;

import com.feature_flag.platform.tenant.entity.Membership;
import com.feature_flag.platform.tenant.enums.MembershipStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MembershipRepository extends JpaRepository<Membership, Long> {

    List<Membership> findByUserId(Long userId);

    /**
     * Every membership row of a tenant, REMOVED ones included. Use it for lifecycle work that must see the
     * whole table (deleting a tenant, re-activating a former member); the members API wants
     * {@link #findByTenantIdAndStatusNot} instead.
     */
    List<Membership> findByTenantId(Long tenantId);

    List<Membership> findByTenantIdAndStatusNot(Long tenantId, MembershipStatus status);

    Optional<Membership> findByIdAndTenantIdAndStatusNot(Long id, Long tenantId, MembershipStatus status);

    Optional<Membership> findByUserIdAndTenantId(Long userId, Long tenantId);

    /**
     * Roles fetched eagerly: TenantContextFilter resolves them outside any transaction, so a lazy
     * {@code Membership.roles} would fail rather than silently relying on open-in-view.
     */
    @EntityGraph(attributePaths = "roles")
    Optional<Membership> findWithRolesByUserIdAndTenantId(Long userId, Long tenantId);

    boolean existsByUserIdAndTenantId(Long userId, Long tenantId);

    /**
     * Tenant-deletion cascade, step one of two.
     *
     * <p>{@code Membership.roles} is an owning {@code @ManyToMany}, so the {@code membership_roles} join
     * rows are Hibernate's to clean up — and a <em>bulk</em> delete of the memberships bypasses the
     * persistence context entirely and would leave them behind, pointing at membership ids that no longer
     * exist. Hibernate has no HQL for a join table, so this one statement is native. It must run before
     * {@link #deleteAllByTenantId(Long)}.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "delete from membership_roles where membership_id in "
            + "(select id from memberships where tenant_id = :tenantId)", nativeQuery = true)
    int deleteMembershipRolesByTenantId(@Param("tenantId") Long tenantId);

    /**
     * Tenant-deletion cascade, step two: the membership rows themselves, once nothing references them.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Membership m where m.tenant.id = :tenantId")
    int deleteAllByTenantId(@Param("tenantId") Long tenantId);
}
