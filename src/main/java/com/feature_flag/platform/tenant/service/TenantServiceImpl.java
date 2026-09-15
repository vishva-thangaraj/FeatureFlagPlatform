package com.feature_flag.platform.tenant.service;

import com.feature_flag.platform.auth.entity.Role;
import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.auth.enums.RoleName;
import com.feature_flag.platform.auth.repository.RoleRepository;
import com.feature_flag.platform.auth.repository.UserRepository;
import com.feature_flag.platform.common.config.I18N;
import com.feature_flag.platform.common.config.Messages;
import com.feature_flag.platform.common.exception.ResourceNotFoundException;
import com.feature_flag.platform.common.security.TenantMembershipResolver;
import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.tenant.dto.TenantCreationRequest;
import com.feature_flag.platform.tenant.dto.TenantList;
import com.feature_flag.platform.tenant.dto.TenantResponse;
import com.feature_flag.platform.tenant.dto.UpdateTenantRequest;
import com.feature_flag.platform.tenant.entity.Membership;
import com.feature_flag.platform.tenant.entity.Tenant;
import com.feature_flag.platform.tenant.enums.MembershipStatus;
import com.feature_flag.platform.tenant.enums.TenantPlan;
import com.feature_flag.platform.tenant.mapper.TenantMapper;
import com.feature_flag.platform.tenant.repository.ApiKeyRepository;
import com.feature_flag.platform.tenant.repository.MembershipRepository;
import com.feature_flag.platform.tenant.repository.TenantRepository;
import jakarta.validation.Valid;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * {@code /api/v1/tenants/{tenantId}} is the one place where the tenant a request acts on comes from the
 * path rather than from {@code X-Tenant-Id}, so every single-tenant operation here authorizes the caller
 * against the <em>path</em> tenant: an ACTIVE membership in it to read, and the OWNER role in it to rename
 * or delete. {@code TenantContextFilter} now resolves the same path tenant before the declarative role
 * rules run; the checks below are deliberately duplicated so the service stays safe on its own.
 */
@Service
public class TenantServiceImpl implements TenantService {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final RoleRepository roleRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final TenantMembershipResolver tenantMembershipResolver;
    private final TenantMapper tenantMapper;

    public TenantServiceImpl(TenantRepository tenantRepository,
                             UserRepository userRepository,
                             MembershipRepository membershipRepository,
                             RoleRepository roleRepository,
                             ApiKeyRepository apiKeyRepository,
                             TenantMembershipResolver tenantMembershipResolver,
                             TenantMapper tenantMapper) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.roleRepository = roleRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.tenantMembershipResolver = tenantMembershipResolver;
        this.tenantMapper = tenantMapper;
    }

    @Override
    @Transactional
    public TenantResponse createTenant(@Valid TenantCreationRequest tenantRequest, TenantUserDetails userDetails) {
        Tenant savedTenant = tenantRepository.save(Tenant.builder()
                .name(tenantRequest.name())
                .plan(TenantPlan.FREE)
                .build());

        Long userId = userDetails.getUserId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        I18N.getMessage(Messages.RESOURCE_USER_NOT_FOUND)));

        Role ownerRole = roleRepository.findByName(RoleName.OWNER.name())
                .orElseThrow(() -> new ResourceNotFoundException(
                        I18N.getMessage(Messages.RESOURCE_ROLE_NOT_FOUND, RoleName.OWNER.name())));

        Membership ownerMembership = Membership.builder()
                .tenant(savedTenant)
                .user(user)
                .roles(Set.of(ownerRole))
                .status(MembershipStatus.ACTIVE)
                .build();
        membershipRepository.save(ownerMembership);

        return tenantMapper.toTenantResponse(savedTenant, List.of(ownerRole));
    }

    @Override
    public TenantList getTenantsForUser(TenantUserDetails userDetails) {
        Long userId = userDetails.getUserId();
        List<Membership> memberships = membershipRepository.findByUserId(userId);

        List<TenantResponse> tenantResponses = memberships.stream()
                .map(tenantMapper::toTenantResponse)
                .toList();

        return new TenantList(tenantResponses);
    }

    /**
     * Reading a tenant needs nothing more than belonging to it — but it does need that. The lookup used to
     * accept any membership row, so a REMOVED or still-INVITED member kept read access.
     */
    @Override
    public TenantResponse getTenant(Long tenantId, TenantUserDetails userDetails) {
        Membership membership =
                tenantMembershipResolver.requireActiveMembership(userDetails.getUserId(), tenantId);

        return tenantMapper.toTenantResponse(membership);
    }

    /** Renaming a tenant is an OWNER action, and OWNER <em>of this tenant</em> — not of some other one. */
    @Override
    @Transactional
    public TenantResponse updateTenant(Long tenantId, UpdateTenantRequest request, TenantUserDetails userDetails) {
        Membership membership = tenantMembershipResolver.requireActiveMembershipWithAnyRole(
                userDetails.getUserId(), tenantId, RoleName.OWNER);

        Tenant tenant = membership.getTenant();
        if (request.name() != null) {
            tenant.setName(request.name());
        }
        tenantRepository.save(tenant);

        return tenantMapper.toTenantResponse(membership);
    }

    /**
     * Deleting a tenant had no authorization at all: it looked the tenant up by id and dropped it, so any
     * authenticated caller who got past the (wrongly scoped) role filter could destroy any tenant on the
     * platform. It now requires the OWNER role in the very tenant named in the path.
     *
     * <p>Memberships and API keys are the tenant's own children and hold FKs to it, so they go first.
     * Invitations, features and environments are owned by other modules and are not cleaned up here — see
     * the note in API_REFERENCE.md.
     */
    @Override
    @Transactional
    public void deleteTenant(Long tenantId, TenantUserDetails userDetails) {
        tenantMembershipResolver.requireActiveMembershipWithAnyRole(
                userDetails.getUserId(), tenantId, RoleName.OWNER);

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        I18N.getMessage(Messages.RESOURCE_TENANT_NOT_FOUND)));

        apiKeyRepository.deleteAll(apiKeyRepository.findByTenantId(tenantId));
        membershipRepository.deleteAll(membershipRepository.findByTenantId(tenantId));

        tenantRepository.delete(tenant);
    }
}