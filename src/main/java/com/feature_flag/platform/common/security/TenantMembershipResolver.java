package com.feature_flag.platform.common.security;

import com.feature_flag.platform.auth.entity.Role;
import com.feature_flag.platform.common.config.I18N;
import com.feature_flag.platform.common.config.Messages;
import com.feature_flag.platform.common.exception.TenantAccessDeniedException;
import com.feature_flag.platform.tenant.entity.Membership;
import com.feature_flag.platform.tenant.enums.MembershipStatus;
import com.feature_flag.platform.tenant.repository.MembershipRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Single place that answers "may this user act in this tenant, and with which roles".
 *
 * <p>Login, refresh, switch-tenant and {@code TenantContextFilter} all need that answer and each used to
 * derive it independently. Roles are read from the membership rather than from the JWT, so removing a
 * member or changing their role takes effect on their next request instead of at token expiry.
 */
@Component
public class TenantMembershipResolver {

    private final MembershipRepository membershipRepository;

    public TenantMembershipResolver(MembershipRepository membershipRepository) {
        this.membershipRepository = membershipRepository;
    }

    /**
     * @throws TenantAccessDeniedException (403) if the user has no membership in the tenant, or it is not
     *                                    ACTIVE.
     */
    public Membership requireActiveMembership(Long userId, Long tenantId) {
        return requireActiveMembership(userId, tenantId,
                Messages.TENANT_NO_MEMBERSHIP, Messages.TENANT_MEMBERSHIP_NOT_ACTIVE);
    }

    /**
     * As {@link #requireActiveMembership(Long, Long)}, but with caller-supplied message keys. Token
     * refresh reports a membership that has since disappeared differently from a tenant the caller never
     * belonged to.
     */
    public Membership requireActiveMembership(Long userId, Long tenantId,
                                              String missingMessageKey, String inactiveMessageKey) {
        Membership membership = membershipRepository.findWithRolesByUserIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new TenantAccessDeniedException(I18N.getMessage(missingMessageKey)));

        if (membership.getStatus() != MembershipStatus.ACTIVE) {
            throw new TenantAccessDeniedException(I18N.getMessage(inactiveMessageKey));
        }
        return membership;
    }

    public List<String> roleNames(Membership membership) {
        return membership.getRoles().stream()
                .map(Role::getName)
                .toList();
    }
}
