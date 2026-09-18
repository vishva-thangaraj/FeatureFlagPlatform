package com.feature_flag.platform.tenant.repository;

import com.feature_flag.platform.tenant.entity.UserInvitation;
import com.feature_flag.platform.tenant.enums.InvitationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserInvitationRepository extends JpaRepository<UserInvitation, Long> {

    /**
     * Every invitation for an address in a tenant that is still in the given status.
     *
     * <p>Replaces a status-agnostic {@code findByEmailAndTenantId}, which made the duplicate-invite guard
     * permanent: once <em>any</em> row existed for an address in a tenant — expired, DECLINED, or left over
     * from a member who has since been removed — no further invitation could ever be created for it, and
     * there is no cancel path to clear one. Only a live PENDING invitation should block a re-invite.
     *
     * <p>Returns a list rather than a single row on purpose: (email, tenant_id) carries no unique
     * constraint, and re-invitation deliberately leaves the superseded rows in place as history, so a
     * single-result query would start throwing as soon as an address had been invited twice.
     *
     * <p>{@code IgnoreCase} because email addresses are compared case-insensitively everywhere else in the
     * invitation flow; leaving it out would let {@code Bob@corp.com} slip past a guard holding
     * {@code bob@corp.com} under a case-sensitive collation.
     */
    List<UserInvitation> findByEmailIgnoreCaseAndTenantIdAndStatus(String email, Long tenantId,
                                                                   InvitationStatus status);

    Optional<UserInvitation> findByTokenHash(String tokenHash);
}