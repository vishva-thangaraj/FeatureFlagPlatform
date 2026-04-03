package com.feature_flag.platform.tenant.service;

import com.feature_flag.platform.auth.entity.Role;
import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.auth.enums.RoleName;
import com.feature_flag.platform.auth.repository.RoleRepository;
import com.feature_flag.platform.auth.repository.UserRepository;
import com.feature_flag.platform.common.config.I18N;
import com.feature_flag.platform.common.config.Messages;
import com.feature_flag.platform.common.exception.ResourceNotFoundException;
import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.tenant.dto.TenantCreationRequest;
import com.feature_flag.platform.tenant.dto.TenantList;
import com.feature_flag.platform.tenant.dto.TenantResponse;
import com.feature_flag.platform.tenant.entity.Membership;
import com.feature_flag.platform.tenant.entity.Tenant;
import com.feature_flag.platform.tenant.enums.MembershipStatus;
import com.feature_flag.platform.tenant.enums.TenantPlan;
import com.feature_flag.platform.tenant.mapper.TenantMapper;
import com.feature_flag.platform.tenant.repository.MembershipRepository;
import com.feature_flag.platform.tenant.repository.TenantRepository;
import jakarta.validation.Valid;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
public class TenantServiceImpl implements TenantService {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final RoleRepository roleRepository;
    private final TenantMapper tenantMapper;

    public TenantServiceImpl(TenantRepository tenantRepository,
                             UserRepository userRepository,
                             MembershipRepository membershipRepository,
                             RoleRepository roleRepository,
                             TenantMapper tenantMapper) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.roleRepository = roleRepository;
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
}