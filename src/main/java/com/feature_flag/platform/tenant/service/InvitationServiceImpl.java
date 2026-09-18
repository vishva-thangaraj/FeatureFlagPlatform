package com.feature_flag.platform.tenant.service;

import com.feature_flag.platform.auth.dto.AuthResponse;
import com.feature_flag.platform.auth.dto.RegisterRequest;
import com.feature_flag.platform.auth.entity.Role;
import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.auth.enums.RoleName;
import com.feature_flag.platform.auth.repository.RoleRepository;
import com.feature_flag.platform.auth.repository.UserRepository;
import com.feature_flag.platform.auth.service.AuthService;
import com.feature_flag.platform.auth.service.TokenService;
import com.feature_flag.platform.common.config.I18N;
import com.feature_flag.platform.common.config.Messages;
import com.feature_flag.platform.common.exception.ConflictException;
import com.feature_flag.platform.common.exception.ForbiddenException;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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
    private final AuthService authService;
    private final RoleGrantPolicy roleGrantPolicy;

    public InvitationServiceImpl(UserInvitationRepository userInvitationRepository,
                                 UserRepository userRepository,
                                 MembershipRepository membershipRepository,
                                 RoleRepository roleRepository,
                                 TenantRepository tenantRepository,
                                 JwtProperties jwtProperties,
                                 EmailService emailService,
                                 TokenService tokenService,
                                 AuthService authService,
                                 RoleGrantPolicy roleGrantPolicy) {
        this.userInvitationRepository = userInvitationRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.roleRepository = roleRepository;
        this.tenantRepository = tenantRepository;
        this.jwtProperties = jwtProperties;
        this.emailService = emailService;
        this.tokenService = tokenService;
        this.authService = authService;
        this.roleGrantPolicy = roleGrantPolicy;
    }

    /**
     * Accepts an invitation on behalf of the invited person — and only them.
     *
     * <p>Security: the invite token is a bearer capability that also travels in a mail URL's query string,
     * so it leaks through forwarded mail, browser history and proxy logs. It therefore proves only that the
     * <em>invitation</em> is genuine, never that the caller is the invitee. Acceptance is authorized by who
     * the caller is, in one of exactly two ways:
     *
     * <ul>
     *   <li><b>The invited address already has an account.</b> The caller must be authenticated as that
     *       user — {@code caller.getUserId()} is compared to the id of the user the invitation resolves to.
     *       A missing principal is rejected with 401. Note that an unauthenticated request on this
     *       {@code permitAll} path still carries an {@code AnonymousAuthenticationToken}, so a null-check on
     *       {@code SecurityContextHolder} would pass; {@code caller} is resolved with
     *       {@code @AuthenticationPrincipal TenantUserDetails}, which is null for an anonymous principal
     *       (its principal is the String {@code "anonymousUser"}) and so cannot slip through.</li>
     *   <li><b>The invited address has no account yet.</b> The caller registers it here, in the same
     *       request, and the registration's email must equal the invited address. The resulting account is
     *       one the caller just created with a password of their choosing, so issuing tokens for it grants
     *       nothing they did not already control.</li>
     * </ul>
     *
     * <p>Possession of the invite token alone never yields a token pair for an existing account.
     */
    @Override
    @Transactional
    public InvitationResponse acceptInvitation(String rawToken, RegisterRequest registration,
                                               TenantUserDetails caller) {
        UserInvitation invitation = requirePendingInvitation(rawToken);
        String invitedEmail = invitation.getEmail();

        Optional<User> existingUser = userRepository.findByEmail(invitedEmail);
        User invitee;

        if (existingUser.isPresent()) {
            invitee = existingUser.get();
            requireCallerIsInvitee(caller, invitee);
        } else {
            if (registration == null) {
                // Nothing to accept as yet: the client is expected to come back with a registration.
                return new InvitationResponse(
                        I18N.getMessage(Messages.INVITATION_REGISTRATION_REQUIRED),
                        InvitationStatus.PENDING_UNREGISTERED, null);
            }
            if (!invitedEmail.equalsIgnoreCase(registration.email())) {
                throw new ForbiddenException(
                        I18N.getMessage(Messages.INVITATION_REGISTRATION_EMAIL_MISMATCH));
            }
            authService.register(registration);
            invitee = userRepository.findByEmail(invitedEmail)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            I18N.getMessage(Messages.RESOURCE_USER_NOT_FOUND)));
        }

        Role role = roleRepository.findByName(invitation.getRole().name())
                .orElseThrow(() -> new ResourceNotFoundException(
                        I18N.getMessage(Messages.RESOURCE_ROLE_NOT_FOUND, invitation.getRole().name())));

        activateMembership(invitee, invitation.getTenant(), role);

        invitation.setStatus(InvitationStatus.ACCEPTED);
        userInvitationRepository.save(invitation);

        // Scoped to the tenant just joined, with the granted role — a tenant-less, role-less token would
        // 403 on every tenant endpoint until the client happened to call switch-tenant.
        AuthResponse authResponse = tokenService.createTokenPair(
                invitee, invitation.getTenant(), List.of(role.getName()));

        return new InvitationResponse(I18N.getMessage(Messages.INVITATION_ACCEPTED),
                InvitationStatus.ACCEPTED, authResponse);
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

        // 400 for an unknown or wrongly-cased name. The old order asked the database first and only then
        // called RoleName.valueOf, and MySQL's default collation is case-insensitive, so {"role":"admin"}
        // passed the lookup and then threw IllegalArgumentException straight out of the service as a 500.
        RoleName roleName = roleGrantPolicy.requireKnownRole(role);
        // An ADMIN inviting a second address they control as OWNER (or as the dormant SUPER_ADMIN) and
        // accepting it was a complete bypass of the ceiling updateMemberRole tries to enforce.
        roleGrantPolicy.requireMayGrant(userDetails, roleName);

        supersedeOrRejectExistingInvitation(email, tenantId);

        roleRepository.findByName(roleName.name())
                .orElseThrow(() -> new ResourceNotFoundException(
                        I18N.getMessage(Messages.RESOURCE_ROLE_NOT_FOUND, roleName.name())));

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
            throw new ConflictException(
                    I18N.getMessage(Messages.INVITATION_ALREADY_PROCESSED));
        }

        invitation.setStatus(InvitationStatus.DECLINED);
        userInvitationRepository.save(invitation);
    }

    /**
     * Decides whether this address may be invited to this tenant again.
     *
     * <p>The guard used to be {@code findByEmailAndTenantId != null}, with no regard for status: once any
     * invitation existed for an address in a tenant it could never be invited again. Since nothing ever
     * assigns {@code CANCELLED} there is no way to clear one either, so an invite that expired unaccepted,
     * was declined by mistake, or belonged to a member who has since been removed permanently locked that
     * address out of the tenant with a 409.
     *
     * <p>Only a <em>live</em> PENDING invitation blocks — that is the case the guard was actually for, and
     * it still refuses. An invitation that has passed its expiry is stale even though its row still reads
     * PENDING (the status is only rewritten when someone tries to accept it), so it is marked EXPIRED here
     * as it is superseded: leaving it PENDING would let the old token stay usable alongside the new one.
     */
    private void supersedeOrRejectExistingInvitation(String email, Long tenantId) {
        List<UserInvitation> pending = userInvitationRepository
                .findByEmailIgnoreCaseAndTenantIdAndStatus(email, tenantId, InvitationStatus.PENDING);

        Instant now = Instant.now();
        for (UserInvitation invitation : pending) {
            if (invitation.getExpiresAt().isAfter(now)) {
                throw new ConflictException(
                        I18N.getMessage(Messages.USER_INVITATION_ALREADY_EXISTS, email));
            }
            invitation.setStatus(InvitationStatus.EXPIRED);
        }
        userInvitationRepository.saveAll(pending);
    }

    private UserInvitation requirePendingInvitation(String rawToken) {
        String tokenHash = TokenHashUtil.sha256(rawToken);

        UserInvitation invitation = userInvitationRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidTokenException(
                        I18N.getMessage(Messages.INVITATION_TOKEN_INVALID)));

        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new ConflictException(
                    I18N.getMessage(Messages.INVITATION_ALREADY_PROCESSED));
        }

        if (invitation.getExpiresAt().isBefore(Instant.now())) {
            invitation.setStatus(InvitationStatus.EXPIRED);
            userInvitationRepository.save(invitation);
            throw new InvalidTokenException(I18N.getMessage(Messages.INVITATION_EXPIRED));
        }
        return invitation;
    }

    /**
     * The whole point of the fix: the caller must <em>be</em> the invited user, not merely hold the token
     * addressed to them.
     */
    private void requireCallerIsInvitee(TenantUserDetails caller, User invitee) {
        if (caller == null || caller.getUserId() == null) {
            throw new UnauthorizedException(
                    I18N.getMessage(Messages.INVITATION_AUTHENTICATION_REQUIRED));
        }
        if (!caller.getUserId().equals(invitee.getId())) {
            throw new ForbiddenException(
                    I18N.getMessage(Messages.INVITATION_NOT_FOR_CALLER));
        }
    }

    /**
     * Idempotent: {@code memberships} is unique on (user, tenant), so re-inviting someone who is already a
     * member — or re-inviting a removed member — has to update the row rather than insert a second one,
     * which used to surface as an unhandled DataIntegrityViolationException (500).
     */
    private void activateMembership(User user, Tenant tenant, Role role) {
        Membership membership = membershipRepository
                .findByUserIdAndTenantId(user.getId(), tenant.getId())
                .orElseGet(() -> Membership.builder()
                        .user(user)
                        .tenant(tenant)
                        .roles(new HashSet<>())
                        .build());

        membership.setStatus(MembershipStatus.ACTIVE);
        // Mutate in place rather than swapping the set: the entity may be managed.
        membership.getRoles().clear();
        membership.getRoles().add(role);
        membershipRepository.save(membership);
    }
}
