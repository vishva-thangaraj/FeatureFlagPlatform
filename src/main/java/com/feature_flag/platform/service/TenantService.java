package com.feature_flag.platform.service;

import com.feature_flag.platform.config.I18N;
import com.feature_flag.platform.config.JwtProperties;
import com.feature_flag.platform.config.Messages;
import com.feature_flag.platform.dto.tenant.TenantCreationRequest;
import com.feature_flag.platform.dto.tenant.TenantList;
import com.feature_flag.platform.dto.tenant.TenantResponse;
import com.feature_flag.platform.entity.*;
import com.feature_flag.platform.enums.InvitationStatus;
import com.feature_flag.platform.enums.MembershipStatus;
import com.feature_flag.platform.enums.RoleName;
import com.feature_flag.platform.enums.TenantPlan;
import com.feature_flag.platform.exception.ResourceNotFoundException;
import com.feature_flag.platform.repository.*;
import com.feature_flag.platform.security.TenantUserDetails;
import com.feature_flag.platform.security.TokenHashUtil;
import jakarta.validation.Valid;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class TenantService {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final RoleRepository roleRepository;
    private final UserInvitationRepository userInvitationRepository;
    private final JwtProperties jwtProperties;
    private final EmailService emailService;

    public TenantService(TenantRepository tenantRepository,
                         UserRepository userRepository,
                         MembershipRepository membershipRepository,
                         RoleRepository roleRepository,
                         UserInvitationRepository userInvitationRepository,
                         JwtProperties jwtProperties,
                         EmailService emailService) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.roleRepository = roleRepository;
        this.userInvitationRepository = userInvitationRepository;
        this.jwtProperties = jwtProperties;
        this.emailService = emailService;
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

    @Transactional
    public void inviteUserToTenant(TenantUserDetails userDetails, String email, String role) {
        Long tenantID = userDetails.getTenantId();
        if (tenantID == null) {
            throw new ResourceNotFoundException(I18N.getMessage(Messages.TENANT_NO_ACTIVE_MEMBERSHIP));
        }
        Tenant tenant = tenantRepository.findById(tenantID)
                .orElseThrow(() -> new ResourceNotFoundException(
                        I18N.getMessage(Messages.RESOURCE_TENANT_NOT_FOUND, tenantID)));

        UserInvitation userInvitation = userInvitationRepository.findByEmailAndTenantId(email, tenantID);
        if (userInvitation != null) {
            throw new IllegalStateException(I18N.getMessage(Messages.USER_INVITATION_ALREADY_EXISTS, email));
        }

        roleRepository.findByName(role)
                .orElseThrow(() -> new ResourceNotFoundException(
                        I18N.getMessage(Messages.RESOURCE_ROLE_NOT_FOUND, role)));
        RoleName roleName = RoleName.valueOf(role);

        Long userId = userDetails.getUserId();
        User inviter = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        I18N.getMessage(Messages.RESOURCE_USER_NOT_FOUND)));

        String rawToken = UUID.randomUUID().toString();
        String tokenHash = TokenHashUtil.sha256(rawToken);

        UserInvitation invitation = UserInvitation.builder()
                .email(email)
                .tenant(tenant)
                .role(roleName)
                .status(InvitationStatus.PENDING)
                .tokenHash(tokenHash)
                .invitedBy(inviter)
                .expiresAt(Instant.now().plusMillis(jwtProperties.getInvitationExpirationMs()))
                .build();

        userInvitationRepository.save(invitation);

        emailService.sendInvitationEmail(email, tenant.getName(), rawToken);
    }
}
