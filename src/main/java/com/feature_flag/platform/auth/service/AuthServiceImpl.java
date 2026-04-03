package com.feature_flag.platform.auth.service;

import com.feature_flag.platform.auth.dto.AuthResponse;
import com.feature_flag.platform.auth.dto.LoginRequest;
import com.feature_flag.platform.auth.dto.RegisterRequest;
import com.feature_flag.platform.auth.dto.SwitchTenantRequest;
import com.feature_flag.platform.auth.entity.Role;
import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.auth.enums.UserStatus;
import com.feature_flag.platform.auth.repository.RoleRepository;
import com.feature_flag.platform.auth.repository.UserRepository;
import com.feature_flag.platform.common.config.I18N;
import com.feature_flag.platform.common.config.Messages;
import com.feature_flag.platform.common.exception.EmailAlreadyExistsException;
import com.feature_flag.platform.common.exception.InvalidCredentialsException;
import com.feature_flag.platform.common.exception.ResourceNotFoundException;
import com.feature_flag.platform.common.exception.TenantAccessDeniedException;
import com.feature_flag.platform.common.exception.UserSuspendedException;
import com.feature_flag.platform.invitation.entity.UserInvitation;
import com.feature_flag.platform.invitation.enums.InvitationStatus;
import com.feature_flag.platform.invitation.repository.UserInvitationRepository;
import com.feature_flag.platform.tenant.entity.Membership;
import com.feature_flag.platform.tenant.enums.MembershipStatus;
import com.feature_flag.platform.tenant.repository.MembershipRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Set;

@Service
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final RoleRepository roleRepository;
    private final UserInvitationRepository userInvitationRepository;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;

    public AuthServiceImpl(UserRepository userRepository,
                           MembershipRepository membershipRepository,
                           RoleRepository roleRepository,
                           UserInvitationRepository userInvitationRepository,
                           TokenService tokenService,
                           PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.roleRepository = roleRepository;
        this.userInvitationRepository = userInvitationRepository;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException(
                    I18N.getMessage(Messages.AUTH_EMAIL_ALREADY_EXISTS, request.email()));
        }

        User user = User.builder()
                .email(request.email())
                .firstName(request.firstName())
                .lastName(request.lastName())
                .passwordHash(passwordEncoder.encode(request.password()))
                .status(UserStatus.ACTIVE)
                .build();
        user = userRepository.save(user);

        // Auto-link any validated invitations for this email
        List<UserInvitation> validatedInvitations = userInvitationRepository
                .findByEmailAndStatus(request.email(), InvitationStatus.VALIDATED);
        for (UserInvitation invitation : validatedInvitations) {
            Role invitationRole = roleRepository.findByName(invitation.getRole().name())
                    .orElseThrow();
            Membership membership = Membership.builder()
                    .user(user)
                    .tenant(invitation.getTenant())
                    .roles(Set.of(invitationRole))
                    .status(MembershipStatus.ACTIVE)
                    .build();
            membershipRepository.save(membership);
            invitation.setStatus(InvitationStatus.ACCEPTED);
            userInvitationRepository.save(invitation);
        }

        return tokenService.createTokenPair(user, null, Collections.emptyList());
    }

    @Override
    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new InvalidCredentialsException(
                        I18N.getMessage(Messages.AUTH_INVALID_CREDENTIALS)));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException(
                    I18N.getMessage(Messages.AUTH_INVALID_CREDENTIALS));
        }

        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new UserSuspendedException(
                    I18N.getMessage(Messages.AUTH_USER_SUSPENDED));
        }

        List<Membership> activeMemberships = membershipRepository.findByUserId(user.getId()).stream()
                .filter(m -> m.getStatus() == MembershipStatus.ACTIVE)
                .toList();

        if (activeMemberships.isEmpty()) {
            return tokenService.createTokenPair(user, null, Collections.emptyList());
        }

        Membership membership;
        if (request.tenantId() != null) {
            membership = activeMemberships.stream()
                    .filter(m -> m.getTenant().getId().equals(request.tenantId()))
                    .findFirst()
                    .orElseThrow(() -> new TenantAccessDeniedException(
                            I18N.getMessage(Messages.TENANT_NO_ACTIVE_MEMBERSHIP)));
        } else {
            membership = activeMemberships.get(0);
        }

        List<String> roles = membership.getRoles().stream()
                .map(Role::getName)
                .toList();

        return tokenService.createTokenPair(user, membership.getTenant(), roles);
    }

    @Override
    @Transactional
    public AuthResponse switchTenant(SwitchTenantRequest request, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        I18N.getMessage(Messages.RESOURCE_USER_NOT_FOUND)));

        Membership membership = membershipRepository.findByUserIdAndTenantId(userId, request.tenantId())
                .orElseThrow(() -> new TenantAccessDeniedException(
                        I18N.getMessage(Messages.TENANT_NO_MEMBERSHIP)));

        if (membership.getStatus() != MembershipStatus.ACTIVE) {
            throw new TenantAccessDeniedException(
                    I18N.getMessage(Messages.TENANT_MEMBERSHIP_NOT_ACTIVE));
        }

        List<String> roles = membership.getRoles().stream()
                .map(Role::getName)
                .toList();

        return tokenService.createTokenPair(user, membership.getTenant(), roles);
    }
}