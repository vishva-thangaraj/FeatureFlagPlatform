package com.feature_flag.platform.tenant.controller;

import com.feature_flag.platform.auth.entity.RefreshToken;
import com.feature_flag.platform.auth.entity.Role;
import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.auth.enums.RoleName;
import com.feature_flag.platform.auth.enums.UserStatus;
import com.feature_flag.platform.auth.repository.RefreshTokenRepository;
import com.feature_flag.platform.auth.repository.RoleRepository;
import com.feature_flag.platform.auth.repository.UserRepository;
import com.feature_flag.platform.common.security.JwtTokenProvider;
import com.feature_flag.platform.common.security.TokenHashUtil;
import com.feature_flag.platform.tenant.entity.Membership;
import com.feature_flag.platform.tenant.entity.Tenant;
import com.feature_flag.platform.tenant.entity.UserInvitation;
import com.feature_flag.platform.tenant.enums.InvitationStatus;
import com.feature_flag.platform.tenant.enums.MembershipStatus;
import com.feature_flag.platform.tenant.repository.MembershipRepository;
import com.feature_flag.platform.tenant.repository.TenantRepository;
import com.feature_flag.platform.tenant.repository.UserInvitationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression tests for the invitation-acceptance account takeover.
 *
 * <p>Accepting an invitation used to be guarded only by {@code getAuthentication() == null}. That check
 * could never fire — the path is {@code permitAll} and anonymous authentication is enabled, so an
 * unauthenticated caller still carries an {@code AnonymousAuthenticationToken} — and it never compared the
 * caller to {@code invitation.getEmail()} anyway. Anyone holding the invite token (which also travels in a
 * mail URL's query string) could POST the accept endpoint and receive the <em>invitee's</em> access and
 * refresh tokens: full takeover of an already-registered account.
 *
 * <p>These tests pin the fix from both ends: only the invited user can accept for an existing account, and
 * the unregistered-invitee registration flow still works.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class InvitationAcceptanceIntegrationTest {

    /** Rate-limit buckets are keyed partly by client address; a fresh one per request keeps them isolated. */
    private static final AtomicInteger CLIENT_ADDRESS_SEQUENCE = new AtomicInteger();

    /** {@code user_invitations.token_hash} is unique, so every invitation needs a distinct raw token. */
    private static final AtomicInteger INVITE_TOKEN_SEQUENCE = new AtomicInteger();

    private static final String ALICE_EMAIL = "alice@corp.com";
    private static final String MALLORY_EMAIL = "mallory@evil.com";
    private static final String NEWCOMER_EMAIL = "newcomer@corp.com";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserInvitationRepository userInvitationRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;

    private Role ownerRole;
    private Role memberRole;

    private Tenant acme;
    private User alice;
    private User mallory;

    private String aliceToken;
    private String malloryToken;

    /** Raw invite token addressed to {@link #ALICE_EMAIL}, as it would arrive in her mail. */
    private String aliceInviteToken;

    @BeforeEach
    void setUp() {
        ownerRole = roleRepository.findByName(RoleName.OWNER.name()).orElseThrow();
        memberRole = roleRepository.findByName(RoleName.MEMBER.name()).orElseThrow();

        acme = tenantRepository.save(Tenant.builder().name("Acme").build());
        Tenant aliceCorp = tenantRepository.save(Tenant.builder().name("Alice Corp").build());
        Tenant evilCorp = tenantRepository.save(Tenant.builder().name("Evil Corp").build());

        User owner = userRepository.save(User.builder()
                .email("owner@acme.com").firstName("Owner").passwordHash("hash")
                .status(UserStatus.ACTIVE).build());
        membershipRepository.save(Membership.builder()
                .user(owner).tenant(acme).status(MembershipStatus.ACTIVE)
                .roles(new HashSet<>(Set.of(ownerRole))).build());

        // Alice already has an account (and her own tenant) — she is the takeover target.
        alice = userRepository.save(User.builder()
                .email(ALICE_EMAIL).firstName("Alice").passwordHash("hash")
                .status(UserStatus.ACTIVE).build());
        membershipRepository.save(Membership.builder()
                .user(alice).tenant(aliceCorp).status(MembershipStatus.ACTIVE)
                .roles(new HashSet<>(Set.of(ownerRole))).build());

        mallory = userRepository.save(User.builder()
                .email(MALLORY_EMAIL).firstName("Mallory").passwordHash("hash")
                .status(UserStatus.ACTIVE).build());
        membershipRepository.save(Membership.builder()
                .user(mallory).tenant(evilCorp).status(MembershipStatus.ACTIVE)
                .roles(new HashSet<>(Set.of(ownerRole))).build());

        aliceToken = jwtTokenProvider.generateAccessToken(
                alice.getId(), alice.getEmail(), aliceCorp.getId(), List.of(RoleName.OWNER.name()));
        malloryToken = jwtTokenProvider.generateAccessToken(
                mallory.getId(), mallory.getEmail(), evilCorp.getId(), List.of(RoleName.OWNER.name()));

        aliceInviteToken = createInvitation(ALICE_EMAIL, acme, RoleName.MEMBER);
    }

    private String createInvitation(String email, Tenant tenant, RoleName role) {
        String rawToken = "invite-token-" + INVITE_TOKEN_SEQUENCE.incrementAndGet() + "-for-" + email;
        userInvitationRepository.save(UserInvitation.builder()
                .email(email)
                .tenant(tenant)
                .role(role)
                .status(InvitationStatus.PENDING)
                .tokenHash(TokenHashUtil.sha256(rawToken))
                .expiresAt(Instant.now().plusSeconds(3600))
                .build());
        return rawToken;
    }

    private MockHttpServletRequestBuilder accept(String inviteToken) {
        return post("/api/v1/invitations/{token}/accept", inviteToken)
                .with(request -> {
                    request.setRemoteAddr("10.1.0." + CLIENT_ADDRESS_SEQUENCE.incrementAndGet());
                    return request;
                });
    }

    private List<RefreshToken> refreshTokensOf(Long userId) {
        return refreshTokenRepository.findAll().stream()
                .filter(rt -> rt.getUser().getId().equals(userId))
                .toList();
    }

    private InvitationStatus statusOf(String rawToken) {
        return userInvitationRepository.findByTokenHash(TokenHashUtil.sha256(rawToken))
                .orElseThrow().getStatus();
    }

    // ---------------------------------------------------------------- the exploit

    /**
     * The exploit itself: no credentials at all, just the invite token. Must not mint anything for Alice.
     */
    @Test
    void unauthenticatedCaller_cannotAcceptForAnExistingUser_andGetsNoTokens() throws Exception {
        mockMvc.perform(accept(aliceInviteToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.authResponse").doesNotExist());

        assertThat(refreshTokensOf(alice.getId()))
                .as("no refresh token may be minted for the invitee by an anonymous caller")
                .isEmpty();
        assertThat(membershipRepository.existsByUserIdAndTenantId(alice.getId(), acme.getId())).isFalse();
        assertThat(statusOf(aliceInviteToken)).isEqualTo(InvitationStatus.PENDING);
    }

    /**
     * The same exploit dressed up with a registration body — the account already exists, so this must not
     * become a second way in.
     */
    @Test
    void unauthenticatedCaller_withARegistrationBodyForAnExistingEmail_isRefused() throws Exception {
        mockMvc.perform(accept(aliceInviteToken)
                        .contentType("application/json")
                        .content("""
                                {"email":"alice@corp.com","password":"attacker-pw","firstName":"Not Alice"}"""))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.authResponse").doesNotExist());

        assertThat(refreshTokensOf(alice.getId())).isEmpty();
        assertThat(statusOf(aliceInviteToken)).isEqualTo(InvitationStatus.PENDING);
    }

    /** A real principal, but the wrong one: possession of the token must not be enough. */
    @Test
    void differentAuthenticatedUser_cannotAcceptSomeoneElsesInvitation() throws Exception {
        mockMvc.perform(accept(aliceInviteToken).header("Authorization", "Bearer " + malloryToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.authResponse").doesNotExist());

        assertThat(refreshTokensOf(alice.getId())).isEmpty();
        assertThat(refreshTokensOf(mallory.getId()))
                .as("the invitation is not Mallory's to accept either")
                .isEmpty();
        assertThat(membershipRepository.existsByUserIdAndTenantId(alice.getId(), acme.getId())).isFalse();
        assertThat(membershipRepository.existsByUserIdAndTenantId(mallory.getId(), acme.getId())).isFalse();
        assertThat(statusOf(aliceInviteToken)).isEqualTo(InvitationStatus.PENDING);
    }

    // ---------------------------------------------------------------- the legitimate flows

    @Test
    void invitedUser_authenticatedAsHerself_acceptsSuccessfully() throws Exception {
        mockMvc.perform(accept(aliceInviteToken).header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(InvitationStatus.ACCEPTED.name()))
                .andExpect(jsonPath("$.authResponse.accessToken").exists())
                .andExpect(jsonPath("$.authResponse.userId").value(alice.getId()))
                .andExpect(jsonPath("$.authResponse.email").value(ALICE_EMAIL))
                // The issued token is scoped to the tenant she just joined, with the invited role.
                .andExpect(jsonPath("$.authResponse.tenantId").value(acme.getId()))
                .andExpect(jsonPath("$.authResponse.roles[0]").value(RoleName.MEMBER.name()));

        Optional<Membership> membership =
                membershipRepository.findByUserIdAndTenantId(alice.getId(), acme.getId());
        assertThat(membership).isPresent();
        assertThat(membership.get().getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(statusOf(aliceInviteToken)).isEqualTo(InvitationStatus.ACCEPTED);
    }

    @Test
    void unregisteredInvitee_registersAndAccepts() throws Exception {
        String inviteToken = createInvitation(NEWCOMER_EMAIL, acme, RoleName.ADMIN);

        mockMvc.perform(accept(inviteToken)
                        .contentType("application/json")
                        .content("""
                                {"email":"newcomer@corp.com","password":"a-good-password","firstName":"New"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(InvitationStatus.ACCEPTED.name()))
                .andExpect(jsonPath("$.authResponse.accessToken").exists())
                .andExpect(jsonPath("$.authResponse.email").value(NEWCOMER_EMAIL))
                .andExpect(jsonPath("$.authResponse.tenantId").value(acme.getId()));

        User newcomer = userRepository.findByEmail(NEWCOMER_EMAIL).orElseThrow();
        assertThat(membershipRepository.existsByUserIdAndTenantId(newcomer.getId(), acme.getId())).isTrue();
        assertThat(statusOf(inviteToken)).isEqualTo(InvitationStatus.ACCEPTED);
    }

    /** Without an account and without a registration body there is nothing to accept as. */
    @Test
    void unregisteredInvitee_withoutARegistrationBody_isToldToRegister() throws Exception {
        String inviteToken = createInvitation(NEWCOMER_EMAIL, acme, RoleName.ADMIN);

        mockMvc.perform(accept(inviteToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(InvitationStatus.PENDING_UNREGISTERED.name()))
                .andExpect(jsonPath("$.authResponse").doesNotExist());

        assertThat(userRepository.findByEmail(NEWCOMER_EMAIL)).isEmpty();
        assertThat(statusOf(inviteToken)).isEqualTo(InvitationStatus.PENDING);
    }

    /** Registering under a different address must not let the registrant ride someone else's invite. */
    @Test
    void registrationBodyForADifferentEmail_isRefused() throws Exception {
        String inviteToken = createInvitation(NEWCOMER_EMAIL, acme, RoleName.ADMIN);

        mockMvc.perform(accept(inviteToken)
                        .contentType("application/json")
                        .content("""
                                {"email":"mallory@evil.com","password":"a-good-password","firstName":"Mallory"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.authResponse").doesNotExist());

        assertThat(membershipRepository.existsByUserIdAndTenantId(mallory.getId(), acme.getId())).isFalse();
        assertThat(statusOf(inviteToken)).isEqualTo(InvitationStatus.PENDING);
    }

    /**
     * {@code memberships} has a unique constraint on (user, tenant); the insert used to be unconditional,
     * so re-inviting an existing member produced an unhandled DataIntegrityViolationException (500).
     */
    @Test
    void reInvitingAnExistingMember_updatesTheMembershipInsteadOfFailing() throws Exception {
        membershipRepository.save(Membership.builder()
                .user(alice).tenant(acme).status(MembershipStatus.ACTIVE)
                .roles(new HashSet<>(Set.of(memberRole))).build());

        String inviteToken = createInvitation(ALICE_EMAIL, acme, RoleName.OWNER);

        mockMvc.perform(accept(inviteToken).header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(InvitationStatus.ACCEPTED.name()))
                .andExpect(jsonPath("$.authResponse.roles[0]").value(RoleName.OWNER.name()));

        // Forces the insert/update to hit the database: a second row would break
        // uk_memberships_user_tenant here rather than at commit time.
        membershipRepository.flush();

        assertThat(membershipRepository.findByTenantId(acme.getId()).stream()
                .filter(m -> m.getUser().getId().equals(alice.getId()))
                .toList())
                .singleElement()
                .extracting(Membership::getStatus)
                .isEqualTo(MembershipStatus.ACTIVE);
    }

    /**
     * A registered user who belongs to no tenant yet (self-registered, or removed from their last one)
     * cannot reach this endpoint at all now that acceptance requires authentication: their token carries
     * {@code tenantId = null}, and {@code TenantContextFilter} answers 403 "No active membership" before the
     * controller runs, because {@code /api/v1/invitations/**} is not in its {@code TENANT_LESS_PATHS}.
     *
     * <p>That filter is outside this change's scope. Accepting an invitation is legitimately tenant-less,
     * exactly like {@code switch-tenant}, so the path belongs in that set; enable this test with that
     * one-line change.
     */
    @Test
    void invitedUserWithNoTenantYet_canStillAccept() throws Exception {
        User solo = userRepository.save(User.builder()
                .email("solo@corp.com").firstName("Solo").passwordHash("hash")
                .status(UserStatus.ACTIVE).build());
        // Exactly the token login issues to a user with no active membership.
        String soloToken = jwtTokenProvider.generateAccessToken(
                solo.getId(), solo.getEmail(), null, List.of());
        String inviteToken = createInvitation("solo@corp.com", acme, RoleName.MEMBER);

        mockMvc.perform(accept(inviteToken).header("Authorization", "Bearer " + soloToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(InvitationStatus.ACCEPTED.name()))
                .andExpect(jsonPath("$.authResponse.tenantId").value(acme.getId()));

        assertThat(membershipRepository.existsByUserIdAndTenantId(solo.getId(), acme.getId())).isTrue();
    }
}
