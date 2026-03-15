package com.feature_flag.platform.service;

import com.feature_flag.platform.config.I18N;
import com.feature_flag.platform.config.Messages;
import com.feature_flag.platform.dto.tenant.TenantCreationRequest;
import com.feature_flag.platform.dto.tenant.TenantList;
import com.feature_flag.platform.dto.tenant.TenantResponse;
import com.feature_flag.platform.entity.Membership;
import com.feature_flag.platform.entity.Role;
import com.feature_flag.platform.entity.Tenant;
import com.feature_flag.platform.entity.User;
import com.feature_flag.platform.enums.MembershipStatus;
import com.feature_flag.platform.enums.RoleName;
import com.feature_flag.platform.enums.TenantPlan;
import com.feature_flag.platform.exception.ResourceNotFoundException;
import com.feature_flag.platform.repository.MembershipRepository;
import com.feature_flag.platform.repository.RoleRepository;
import com.feature_flag.platform.repository.TenantRepository;
import com.feature_flag.platform.repository.UserRepository;
import com.feature_flag.platform.security.TenantUserDetails;
import jakarta.validation.Valid;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
public class TenantService {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final RoleRepository roleRepository;

    public TenantService(TenantRepository tenantRepository,
                         UserRepository userRepository,
                         MembershipRepository membershipRepository,
                         RoleRepository roleRepository) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.roleRepository = roleRepository;
    }

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

        return new TenantResponse(savedTenant.getId().toString(), savedTenant.getName(), savedTenant.getPlan(), List.of(ownerRole));
    }

    public TenantList getTenantsForUser(TenantUserDetails userDetails) {
        Long userId = userDetails.getUserId();
        List<Membership> memberships = membershipRepository.findByUserId(userId);

        List<TenantResponse> tenantResponses = memberships.stream()
                .map(membership -> {
                    Tenant tenant = membership.getTenant();
                    List<Role> roles = membership.getRoles().stream().toList();
                    return new TenantResponse(tenant.getId().toString(), tenant.getName(), tenant.getPlan(), roles);
                })
                .toList();

        return new TenantList(tenantResponses);
    }
}
