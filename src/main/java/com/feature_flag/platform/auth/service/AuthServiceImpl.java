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
import com.feature_flag.platform.common.exception.ForbiddenException;
import com.feature_flag.platform.common.exception.InvalidCredentialsException;
import com.feature_flag.platform.common.exception.ResourceNotFoundException;
import com.feature_flag.platform.common.exception.UserSuspendedException;
import com.feature_flag.platform.common.security.TenantMembershipResolver;
import com.feature_flag.platform.tenant.entity.UserInvitation;
import com.feature_flag.platform.tenant.enums.InvitationStatus;
import com.feature_flag.platform.tenant.repository.UserInvitationRepository;
import com.feature_flag.platform.tenant.entity.Membership;
import com.feature_flag.platform.tenant.enums.MembershipStatus;
import com.feature_flag.platform.tenant.repository.MembershipRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class AuthServiceImpl implements AuthService {

    /**
     * Decoy hash for the login miss path. This is a genuine BCrypt digest produced by
     * {@code BCryptPasswordEncoder} at its default strength of 10 — the same strength as the encoder
     * bean in {@code SecurityConfig} — so verifying against it costs the same key stretching as
     * verifying against a real user's hash. It is the digest of a passphrase that belongs to no
     * account and is recorded nowhere, so it can never verify successfully.
     *
     * <p>It must stay a <em>well formed</em> BCrypt string: {@code BCryptPasswordEncoder.matches}
     * short-circuits to {@code false} in microseconds for anything that does not parse as one, which
     * would reintroduce exactly the timing gap this constant exists to close.
     */
    static final String DUMMY_PASSWORD_HASH = "$2a$10$DK1YqO5rjPPH5IltVYZbGeNIkTzBvR5l5l8W2tAR1OcAv3T8ROTAe";

    /**
     * Sink for the decoy comparison's result. Writing to a {@code volatile} field is a side effect the
     * compiler and the JIT are not allowed to remove, so the comparison cannot be folded away as dead
     * code. Nothing ever reads it.
     */
    @SuppressWarnings("unused")
    private static volatile boolean decoyVerificationSink;

    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final RoleRepository roleRepository;
    private final UserInvitationRepository userInvitationRepository;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final TenantMembershipResolver tenantMembershipResolver;

    public AuthServiceImpl(UserRepository userRepository,
                           MembershipRepository membershipRepository,
                           RoleRepository roleRepository,
                           UserInvitationRepository userInvitationRepository,
                           TokenService tokenService,
                           PasswordEncoder passwordEncoder,
                           TenantMembershipResolver tenantMembershipResolver) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.roleRepository = roleRepository;
        this.userInvitationRepository = userInvitationRepository;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.tenantMembershipResolver = tenantMembershipResolver;
    }

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            // /api/v1/auth/register is unauthenticated, so anything this message says about the address
            // is said to an anonymous prober. The old text interpolated the probed address into
            // "Email already in use: {0}", which both echoed attacker-controlled input back and
            // confirmed the account exists — a bulk user-directory oracle. The replacement key takes no
            // arguments at all, so there is nothing to interpolate, and its wording is the same advice
            // a first-time registrant could be given. The check itself stays: it is what keeps the
            // users.email unique constraint from surfacing as a driver-level integrity violation.
            //
            // Residual gap, deliberately left: the 409 status still only occurs for an address that
            // exists. Closing that needs the response shape to be identical either way — register
            // returning a neutral "check your inbox" acknowledgement and the real signal going out by
            // email — which requires AuthController and EmailService, both outside this change's scope.
            // See the report accompanying this commit.
            throw new EmailAlreadyExistsException(
                    I18N.getMessage(Messages.AUTH_REGISTRATION_CONFLICT));
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
        Optional<User> found = userRepository.findByEmail(request.email());

        if (found.isEmpty()) {
            // The two failure responses are byte-identical, but the work behind them was not: a known
            // address always paid for a full BCrypt comparison (tens of ms) while an unknown one
            // returned as fast as the index lookup. That gap is a user-enumeration oracle that needs no
            // more than a stopwatch. Spend the same key stretching on the miss path against a decoy
            // hash, so latency no longer separates "no such user" from "wrong password".
            decoyVerificationSink = passwordEncoder.matches(request.password(), DUMMY_PASSWORD_HASH);
            throw new InvalidCredentialsException(
                    I18N.getMessage(Messages.AUTH_INVALID_CREDENTIALS));
        }

        User user = found.get();

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException(
                    I18N.getMessage(Messages.AUTH_INVALID_CREDENTIALS));
        }

        // Allowlist, not denylist. Naming SUSPENDED alone meant every other UserStatus authenticated
        // normally — INACTIVE included — and any status added later would fail open by default. Only
        // ACTIVE may hold a session; everything else is refused, and the refusal reason stays specific
        // for SUSPENDED because that message is already part of the API.
        if (user.getStatus() != UserStatus.ACTIVE) {
            if (user.getStatus() == UserStatus.SUSPENDED) {
                throw new UserSuspendedException(
                        I18N.getMessage(Messages.AUTH_USER_SUSPENDED));
            }
            throw new ForbiddenException(
                    I18N.getMessage(Messages.AUTH_USER_NOT_ACTIVE), "USER_NOT_ACTIVE");
        }

        Membership membership;
        if (request.tenantId() != null) {
            // Asking for a tenant you have no active membership in is denied rather than silently
            // downgraded to a tenant-less token.
            membership = tenantMembershipResolver.requireActiveMembership(user.getId(), request.tenantId());
        } else {
            // findByUserId carries no ORDER BY, so the old activeMemberships.get(0) picked whichever
            // row the database happened to hand back first. For a user in more than one tenant that
            // made the default tenant — and the roles claim minted from it — liable to change between
            // two logins with identical data, purely on the query plan's whim.
            //
            // Oldest active membership wins. Ids are TSIDs (see BaseEntity), which are generated in
            // ascending time order, so the lowest id is the membership the user has held longest:
            // stable for a given user, independent of the plan, and the tenant a returning user is
            // most likely to think of as home. A null id can only occur for an unpersisted entity, so
            // it sorts last rather than silently winning.
            Optional<Membership> defaultMembership = membershipRepository.findByUserId(user.getId()).stream()
                    .filter(m -> m.getStatus() == MembershipStatus.ACTIVE)
                    .min(Comparator.comparing(Membership::getId,
                            Comparator.nullsLast(Comparator.naturalOrder())));

            if (defaultMembership.isEmpty()) {
                return tokenService.createTokenPair(user, null, Collections.emptyList());
            }
            membership = defaultMembership.get();
        }

        return tokenService.createTokenPair(user, membership.getTenant(),
                tenantMembershipResolver.roleNames(membership));
    }

    @Override
    @Transactional
    public AuthResponse switchTenant(SwitchTenantRequest request, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        I18N.getMessage(Messages.RESOURCE_USER_NOT_FOUND)));

        Membership membership = tenantMembershipResolver.requireActiveMembership(userId, request.tenantId());

        return tokenService.createTokenPair(user, membership.getTenant(),
                tenantMembershipResolver.roleNames(membership));
    }
}