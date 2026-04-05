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
import java.util.Set;

@Service
public class MembershipService {
    private final MembershipRepository membershipRepository;
    private final MembershipListResponseMapper membershipListResponseMapper;
    private final RoleRepository roleRepository;

    public MembershipService(MembershipRepository membershipRepository,
                             MembershipListResponseMapper membershipListResponseMapper,
                             RoleRepository roleRepository) {
        this.membershipRepository = membershipRepository;
        this.membershipListResponseMapper = membershipListResponseMapper;
        this.roleRepository = roleRepository;
    }

    public MembersListResponse getMembersList(Long tenantId) {
        List<Membership> memberships = membershipRepository.findByTenantId(tenantId);
        List<MemberResponse> members = membershipListResponseMapper.toMemberResponses(memberships);
        return new MembersListResponse(members);
    }

    public MemberResponse getMember(Long membershipId, Long tenantId) {
        Membership membership = findByIdAndTenant(membershipId, tenantId);
        return membershipListResponseMapper.toMemberResponses(List.of(membership)).get(0);
    }

    @Transactional
    public MemberResponse updateMemberRole(Long membershipId, UpdateMemberRoleRequest request, Long tenantId) {
        Membership membership = findByIdAndTenant(membershipId, tenantId);

        if (hasRole(membership, RoleName.OWNER)) {
            throw new ForbiddenException(I18N.getMessage(Messages.TENANT_CANNOT_CHANGE_OWNER_ROLE));
        }

        Role newRole = roleRepository.findByName(request.roleName())
                .orElseThrow(() -> new ResourceNotFoundException(
                        I18N.getMessage(Messages.RESOURCE_ROLE_NOT_FOUND, request.roleName())));

        membership.setRoles(Set.of(newRole));
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

    private Membership findByIdAndTenant(Long membershipId, Long tenantId) {
        return membershipRepository.findById(membershipId)
                .filter(m -> m.getTenant().getId().equals(tenantId))
                .orElseThrow(() -> new ResourceNotFoundException(
                        I18N.getMessage(Messages.RESOURCE_MEMBERSHIP_NOT_FOUND)));
    }

    private boolean hasRole(Membership membership, RoleName roleName) {
        return membership.getRoles().stream()
                .anyMatch(r -> r.getName().equals(roleName.name()));
    }
}
