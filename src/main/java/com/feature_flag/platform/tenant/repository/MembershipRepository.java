package com.feature_flag.platform.tenant.repository;

import com.feature_flag.platform.tenant.entity.Membership;
import com.feature_flag.platform.tenant.enums.MembershipStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
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
}