package com.feature_flag.platform.tenant.service;

import com.feature_flag.platform.auth.entity.Role;
import com.feature_flag.platform.auth.enums.RoleName;
import com.feature_flag.platform.auth.repository.RoleRepository;
import com.feature_flag.platform.common.config.I18N;
import com.feature_flag.platform.common.config.Messages;
import com.feature_flag.platform.common.exception.ForbiddenException;
import com.feature_flag.platform.common.exception.ResourceNotFoundException;
import com.feature_flag.platform.tenant.dto.MemberResponse;
import com.feature_flag.platform.tenant.dto.MembersListResponse;
import com.feature_flag.platform.tenant.dto.UpdateMemberRoleRequest;
import com.feature_flag.platform.tenant.entity.Membership;
import com.feature_flag.platform.tenant.enums.MembershipStatus;
import com.feature_flag.platform.tenant.mapper.MembershipListResponseMapper;
import com.feature_flag.platform.tenant.repository.MembershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class MembershipService {
    private final MembershipRepository membershipRepository;
    private final MembershipListResponseMapper membershipListResponseMapper;
    private final RoleRepository roleRepository;
    private final RoleGrantPolicy roleGrantPolicy;

    public MembershipService(MembershipRepository membershipRepository,
                             MembershipListResponseMapper membershipListResponseMapper,
                             RoleRepository roleRepository,
                             RoleGrantPolicy roleGrantPolicy) {
        this.membershipRepository = membershipRepository;
        this.membershipListResponseMapper = membershipListResponseMapper;
        this.roleRepository = roleRepository;
        this.roleGrantPolicy = roleGrantPolicy;
    }

    /**
     * REMOVED memberships are excluded. The row survives removal (it is the audit trail, and the unique
     * (user, tenant) constraint means re-adding someone reuses it), but it is not a membership any more:
     * {@code TenantMembershipResolver} already refuses every request such a user makes, so listing them
     * here had the admin UI and the auth layer disagreeing about who belongs to the tenant.
     */
    public MembersListResponse getMembersList(Long tenantId) {
        List<Membership> memberships =
                membershipRepository.findByTenantIdAndStatusNot(tenantId, MembershipStatus.REMOVED);
        List<MemberResponse> members = membershipListResponseMapper.toMemberResponses(memberships);
        return new MembersListResponse(members);
    }

    public MemberResponse getMember(Long membershipId, Long tenantId) {
        Membership membership = findByIdAndTenant(membershipId, tenantId);
        return membershipListResponseMapper.toMemberResponses(List.of(membership)).get(0);
    }

    /**
     * The guard below only ever refused to change an <em>existing</em> OWNER's role, which says nothing
     * about the role being handed out. Since {@code api-security.json} opens this endpoint to ADMIN as well
     * as OWNER, an ADMIN could PUT {@code {"roleName":"OWNER"}} onto any non-owner membership — their own
     * included — and promote themselves past the ceiling the owner guard was meant to defend.
     * {@link RoleGrantPolicy} now caps the granted role at the caller's own rank in this tenant.
     */
    @Transactional
    public MemberResponse updateMemberRole(Long membershipId, UpdateMemberRoleRequest request, Long tenantId) {
        Membership membership = findByIdAndTenant(membershipId, tenantId);

        if (hasRole(membership, RoleName.OWNER)) {
            throw new ForbiddenException(I18N.getMessage(Messages.TENANT_CANNOT_CHANGE_OWNER_ROLE));
        }

        // 400 for an unknown or wrongly-cased name, before any authorization decision is taken on it.
        RoleName requested = roleGrantPolicy.requireKnownRole(request.roleName());
        roleGrantPolicy.requireCurrentCallerMayGrant(requested);

        Role newRole = roleRepository.findByName(requested.name())
                .orElseThrow(() -> new ResourceNotFoundException(
                        I18N.getMessage(Messages.RESOURCE_ROLE_NOT_FOUND, requested.name())));

        // Mutate in place rather than swapping the set, exactly as InvitationServiceImpl.activateMembership
        // does. Assigning Set.of(...) put an immutable collection on a managed entity, and the merge that
        // save() performs on it calls clear() on that collection — an UnsupportedOperationException, i.e. a
        // 500, for any role change made while the membership was already in the persistence context (a
        // second change in one request, or one following a read of the same membership).
        membership.getRoles().clear();
        membership.getRoles().add(newRole);
        membershipRepository.save(membership);

        return membershipListResponseMapper.toMemberResponses(List.of(membership)).get(0);
    }

    @Transactional
    public void removeMember(Long membershipId, Long tenantId) {
        Membership membership = findByIdAndTenant(membershipId, tenantId);

        if (hasRole(membership, RoleName.OWNER)) {
            throw new ForbiddenException(I18N.getMessage(Messages.TENANT_CANNOT_REMOVE_OWNER));
        }

        membership.setStatus(MembershipStatus.REMOVED);
        membershipRepository.save(membership);
    }

    /**
     * Resolves a membership of this tenant that is still a membership.
     *
     * <p>A REMOVED row is deliberately invisible here, so reading, re-roling or re-removing one answers 404
     * rather than succeeding against someone who is no longer a member. The status filter is part of the
     * lookup rather than a check bolted on afterwards, so every caller gets it.
     */
    private Membership findByIdAndTenant(Long membershipId, Long tenantId) {
        return membershipRepository
                .findByIdAndTenantIdAndStatusNot(membershipId, tenantId, MembershipStatus.REMOVED)
                .orElseThrow(() -> new ResourceNotFoundException(
                        I18N.getMessage(Messages.RESOURCE_MEMBERSHIP_NOT_FOUND)));
    }

    private boolean hasRole(Membership membership, RoleName roleName) {
        return membership.getRoles().stream()
                .anyMatch(r -> r.getName().equals(roleName.name()));
    }
}
