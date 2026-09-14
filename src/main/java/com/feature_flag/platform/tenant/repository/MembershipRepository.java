package com.feature_flag.platform.tenant.repository;

import com.feature_flag.platform.tenant.entity.Membership;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MembershipRepository extends JpaRepository<Membership, Long> {

    List<Membership> findByUserId(Long userId);

    List<Membership> findByTenantId(Long tenantId);

    Optional<Membership> findByUserIdAndTenantId(Long userId, Long tenantId);

    /**
     * Roles fetched eagerly: TenantContextFilter resolves them outside any transaction, so a lazy
     * {@code Membership.roles} would fail rather than silently relying on open-in-view.
     */
    @EntityGraph(attributePaths = "roles")
    Optional<Membership> findWithRolesByUserIdAndTenantId(Long userId, Long tenantId);

    boolean existsByUserIdAndTenantId(Long userId, Long tenantId);
}