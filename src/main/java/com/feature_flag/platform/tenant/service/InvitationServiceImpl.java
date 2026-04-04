package com.feature_flag.platform.tenant.service;

import com.feature_flag.platform.auth.dto.AuthResponse;
import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.auth.enums.RoleName;
import com.feature_flag.platform.auth.repository.RoleRepository;
import com.feature_flag.platform.auth.repository.UserRepository;
import com.feature_flag.platform.auth.service.TokenService;
import com.feature_flag.platform.common.config.I18N;
import com.feature_flag.platform.common.config.Messages;
import com.feature_flag.platform.common.exception.InvalidTokenException;
import com.feature_flag.platform.common.exception.ResourceNotFoundException;
import com.feature_flag.platform.common.exception.UnauthorizedException;
import com.feature_flag.platform.common.security.JwtProperties;
import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.common.security.TokenHashUtil;
import com.feature_flag.platform.tenant.dto.InvitationResponse;
import com.feature_flag.platform.tenant.entity.UserInvitation;
import com.feature_flag.platform.tenant.enums.InvitationStatus;
import com.feature_flag.platform.tenant.repository.UserInvitationRepository;
import com.feature_flag.platform.notification.service.EmailService;
import com.feature_flag.platform.tenant.entity.Membership;
import com.feature_flag.platform.tenant.entity.Tenant;
import com.feature_flag.platform.tenant.enums.MembershipStatus;
import com.feature_flag.platform.tenant.repository.MembershipRepository;
import com.feature_flag.platform.tenant.repository.TenantRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class InvitationServiceImpl implements InvitationService {

    private final UserInvitationRepository userInvitationRepository;
    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final RoleRepository roleRepository;
    private final TenantRepository tenantRepository;
    private final JwtProperties jwtProperties;
    private final EmailService emailService;
    private final TokenService tokenService;

    public InvitationServiceImpl(UserInvitationRepository userInvitationRepository,
                                 UserRepository userRepository,
                                 MembershipRepository membershipRepository,
                                 RoleRepository roleRepository,
                                 TenantRepository tenantRepository,
                                 JwtProperties jwtProperties,
                                 EmailService emailService, TokenService tokenService) {
        this.userInvitationRepository = userInvitationRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.roleRepository = roleRepository;
        this.tenantRepository = tenantRepository;
        this.jwtProperties = jwtProperties;
        this.emailService = emailService;
        this.tokenService = tokenService;
    }

    @Override
    @Transactional
    public InvitationResponse acceptInvitation(String rawToken) {
        String tokenHash = TokenHashUtil.sha256(rawToken);

        UserInvitation invitation = userInvitationRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidTokenException(
                        I18N.getMessage(Messages.INVITATION_TOKEN_INVALID)));

        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new IllegalStateException(
                    I18N.getMessage(Messages.INVITATION_ALREADY_PROCESSED));
        }

        if (invitation.getExpiresAt().isBefore(Instant.now())) {
            invitation.setStatus(InvitationStatus.EXPIRED);
            userInvitationRepository.save(invitation);
            throw new InvalidTokenException(
                    I18N.getMessage(Messages.INVITATION_EXPIRED));
        }

        Optional<User> existingUser = userRepository.findByEmail(invitation.getEmail());

        if (existingUser.isPresent()) {
            if(SecurityContextHolder.getContext().getAuthentication() == null)
            {
                throw new UnauthorizedException(
                        I18N.getMessage(Messages.UNAUTHORIZED));
            }
            createMembership(existingUser.get(), invitation);
            invitation.setStatus(InvitationStatus.ACCEPTED);
            userInvitationRepository.save(invitation);
            AuthResponse authResponse = tokenService.createTokenPair(existingUser.get(), null, Collections.emptyList());
            return new InvitationResponse("Invitation accepted", InvitationStatus.ACCEPTED, authResponse);
        } else {

            return new InvitationResponse("Please register to complete your invitation", InvitationStatus.PENDING_UNREGISTERED, null);
        }
    }

    @Override
    @Transactional
    public void inviteUserToTenant(TenantUserDetails userDetails, String email, String role) {
        Long tenantId = userDetails.getTenantId();
        if (tenantId == null) {
            throw new ResourceNotFoundException(I18N.getMessage(Messages.TENANT_NO_ACTIVE_MEMBERSHIP));
        }
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        I18N.getMessage(Messages.RESOURCE_TENANT_NOT_FOUND, tenantId)));

        UserInvitation existingInvitation = userInvitationRepository.findByEmailAndTenantId(email, tenantId);
        if (existingInvitation != null) {
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

    @Override
    public InvitationResponse getStatus(String token) {
        String tokenHash = TokenHashUtil.sha256(token);

        UserInvitation invitation  = userInvitationRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidTokenException(
                        I18N.getMessage(Messages.INVITATION_TOKEN_INVALID)));

        InvitationStatus status = invitation.getStatus();

        String email = invitation.getEmail();

        Optional<User> userOpt = userRepository.findByEmail(email);
        if(userOpt.isEmpty() && status == InvitationStatus.PENDING)
        {
            return new InvitationResponse("Invitation is pending. Please register to accept the invitation.", status, null);
        }
        return new InvitationResponse("Invitation status: " + status.name(), status, null);
    }

    @Override
    public void declineInvitation(String token) {
        String tokenHash = TokenHashUtil.sha256(token);

        UserInvitation invitation  = userInvitationRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidTokenException(
                        I18N.getMessage(Messages.INVITATION_TOKEN_INVALID)));

        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new IllegalStateException(
                    I18N.getMessage(Messages.INVITATION_ALREADY_PROCESSED));
        }

        invitation.setStatus(InvitationStatus.DECLINED);
        userInvitationRepository.save(invitation);
    }

    private void createMembership(User user, UserInvitation invitation) {
        var role = roleRepository.findByName(invitation.getRole().name())
                .orElseThrow();

        Membership membership = Membership.builder()
                .user(user)
                .tenant(invitation.getTenant())
                .roles(Set.of(role))
                .status(MembershipStatus.ACTIVE)
                .build();
        membershipRepository.save(membership);
    }
}
