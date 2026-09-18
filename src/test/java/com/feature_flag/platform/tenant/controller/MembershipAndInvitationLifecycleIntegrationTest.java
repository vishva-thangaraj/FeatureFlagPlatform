package com.feature_flag.platform.tenant.controller;

import com.feature_flag.platform.auth.entity.Role;
import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.auth.enums.RoleName;
import com.feature_flag.platform.auth.enums.UserStatus;
import com.feature_flag.platform.auth.repository.RoleRepository;
import com.feature_flag.platform.auth.repository.UserRepository;
import com.feature_flag.platform.common.security.JwtTokenProvider;
import com.feature_flag.platform.common.security.TokenHashUtil;
import com.feature_flag.platform.notification.service.EmailService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two lifecycle defects that pulled in opposite directions.
 *
 * <ul>
 *   <li><b>REMOVED members stayed live.</b> {@code getMembersList} read the whole tenant with no status
 *       filter and {@code findByIdAndTenant} ignored status, so a removed member was still listed by
 *       {@code GET /api/v1/members}, could still have their role changed, and could be "removed" again for
 *       a cheerful 204 — while {@code TenantMembershipResolver} denied them every request. The admin UI and
 *       the auth layer disagreed about who was a member.</li>
 *   <li><b>An address could never be re-invited.</b> The duplicate guard was
 *       {@code findByEmailAndTenantId != null}, with no regard for status, and nothing ever assigns
 *       {@code CANCELLED}. One expired or declined invitation therefore locked that address out of the
 *       tenant permanently with a 409.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MembershipAndInvitationLifecycleIntegrationTest {

    /** Rate-limit buckets are keyed by client address; a fresh one per request keeps them isolated. */
    private static final AtomicInteger CLIENT_ADDRESS_SEQUENCE = new AtomicInteger();

    /** {@code user_invitations.token_hash} is unique, so every seeded invitation needs a distinct token. */
    private static final AtomicInteger INVITE_TOKEN_SEQUENCE = new AtomicInteger();

    private static final String REMOVED_EMAIL = "removed@acme.com";
    private static final String RETURNING_EMAIL = "returning@corp.com";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserInvitationRepository userInvitationRepository;

    @MockitoBean private EmailService emailService;

    private Tenant acme;
    private String ownerToken;
    private Membership activeMembership;
    private Membership removedMembership;

    @BeforeEach
    void setUp() {
        acme = tenantRepository.save(Tenant.builder().name("Acme").build());

        User owner = saveUser("owner@acme.com", "Owner");
        User active = saveUser("active@acme.com", "Active");
        User removed = saveUser(REMOVED_EMAIL, "Removed");

        saveMembership(owner, RoleName.OWNER, MembershipStatus.ACTIVE);
        activeMembership = saveMembership(active, RoleName.MEMBER, MembershipStatus.ACTIVE);
        removedMembership = saveMembership(removed, RoleName.MEMBER, MembershipStatus.REMOVED);

        ownerToken = jwtTokenProvider.generateAccessToken(
                owner.getId(), owner.getEmail(), acme.getId(), List.of(RoleName.OWNER.name()));
    }

    private User saveUser(String email, String firstName) {
        return userRepository.save(User.builder()
                .email(email).firstName(firstName).passwordHash("hash")
                .status(UserStatus.ACTIVE).build());
    }

    private Membership saveMembership(User user, RoleName roleName, MembershipStatus status) {
        Role role = roleRepository.findByName(roleName.name()).orElseThrow();
        return membershipRepository.save(Membership.builder()
                .user(user).tenant(acme).status(status)
                .roles(new HashSet<>(Set.of(role))).build());
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder) {
        return builder
                .header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json")
                .with(request -> {
                    request.setRemoteAddr("10.3.0." + CLIENT_ADDRESS_SEQUENCE.incrementAndGet());
                    return request;
                });
    }

    private MockHttpServletRequestBuilder invite(String email, RoleName role) {
        return as(post("/api/v1/invitations"))
                .content("{\"email\":\"%s\",\"role\":\"%s\"}".formatted(email, role.name()));
    }

    private UserInvitation seedInvitation(String email, InvitationStatus status, Instant expiresAt) {
        return userInvitationRepository.save(UserInvitation.builder()
                .email(email)
                .tenant(acme)
                .role(RoleName.MEMBER)
                .status(status)
                .tokenHash(TokenHashUtil.sha256("seed-" + INVITE_TOKEN_SEQUENCE.incrementAndGet()))
                .expiresAt(expiresAt)
                .build());
    }

    private List<UserInvitation> pendingInvitationsFor(String email) {
        return userInvitationRepository.findByEmailIgnoreCaseAndTenantIdAndStatus(
                email, acme.getId(), InvitationStatus.PENDING);
    }

    // ------------------------------------------------------------------ REMOVED members are not members

    @Test
    void removedMember_isNotListed() throws Exception {
        mockMvc.perform(as(get("/api/v1/members")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.members[?(@.membershipId == %d)]".formatted(removedMembership.getId()))
                        .isEmpty())
                .andExpect(jsonPath("$.members[?(@.membershipId == %d)]".formatted(activeMembership.getId()))
                        .isNotEmpty());
    }

    @Test
    void removedMember_cannotBeFetched() throws Exception {
        mockMvc.perform(as(get("/api/v1/members/{id}", removedMembership.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void removedMember_cannotHaveTheirRoleChanged() throws Exception {
        mockMvc.perform(as(put("/api/v1/members/{id}/role", removedMembership.getId()))
                        .content("{\"roleName\":\"ADMIN\"}"))
                .andExpect(status().isNotFound());

        assertThat(membershipRepository.findById(removedMembership.getId()).orElseThrow().getRoles())
                .extracting(Role::getName)
                .containsExactly(RoleName.MEMBER.name());
    }

    /** Removing an already-removed membership used to answer 204, as if something had happened. */
    @Test
    void removedMember_cannotBeRemovedAgain() throws Exception {
        mockMvc.perform(as(delete("/api/v1/members/{id}", removedMembership.getId())))
                .andExpect(status().isNotFound());
    }

    /** The control: an actual member is still listed, still re-rolable, still removable. */
    @Test
    void activeMember_isUnaffected() throws Exception {
        mockMvc.perform(as(get("/api/v1/members/{id}", activeMembership.getId())))
                .andExpect(status().isOk());

        mockMvc.perform(as(put("/api/v1/members/{id}/role", activeMembership.getId()))
                        .content("{\"roleName\":\"VIEWER\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(as(delete("/api/v1/members/{id}", activeMembership.getId())))
                .andExpect(status().isNoContent());

        assertThat(membershipRepository.findById(activeMembership.getId()).orElseThrow().getStatus())
                .isEqualTo(MembershipStatus.REMOVED);
    }

    // ------------------------------------------------------------------ re-invitation

    /** The guard that must survive: a live PENDING invitation is still a duplicate. */
    @Test
    void duplicatePendingInvitation_isStillRefused() throws Exception {
        seedInvitation(RETURNING_EMAIL, InvitationStatus.PENDING, Instant.now().plusSeconds(3600));

        mockMvc.perform(invite(RETURNING_EMAIL, RoleName.MEMBER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        assertThat(pendingInvitationsFor(RETURNING_EMAIL))
                .as("the refused invite must not have created a second pending row")
                .hasSize(1);
    }

    /**
     * An invitation nobody ever tried to accept keeps status PENDING past its expiry — the status is only
     * rewritten on an acceptance attempt — so this is the case the status-agnostic guard got most wrong.
     */
    @Test
    void invitationThatQuietlyExpired_canBeReInvited() throws Exception {
        UserInvitation stale =
                seedInvitation(RETURNING_EMAIL, InvitationStatus.PENDING, Instant.now().minusSeconds(3600));

        mockMvc.perform(invite(RETURNING_EMAIL, RoleName.MEMBER))
                .andExpect(status().isOk());

        assertThat(userInvitationRepository.findById(stale.getId()).orElseThrow().getStatus())
                .as("the superseded invitation's token must not stay usable alongside the new one")
                .isEqualTo(InvitationStatus.EXPIRED);
        assertThat(pendingInvitationsFor(RETURNING_EMAIL)).hasSize(1);
    }

    @Test
    void invitationMarkedExpired_canBeReInvited() throws Exception {
        seedInvitation(RETURNING_EMAIL, InvitationStatus.EXPIRED, Instant.now().minusSeconds(3600));

        mockMvc.perform(invite(RETURNING_EMAIL, RoleName.MEMBER))
                .andExpect(status().isOk());

        assertThat(pendingInvitationsFor(RETURNING_EMAIL)).hasSize(1);
    }

    @Test
    void declinedInvitation_canBeReInvited() throws Exception {
        seedInvitation(RETURNING_EMAIL, InvitationStatus.DECLINED, Instant.now().plusSeconds(3600));

        mockMvc.perform(invite(RETURNING_EMAIL, RoleName.MEMBER))
                .andExpect(status().isOk());

        assertThat(pendingInvitationsFor(RETURNING_EMAIL)).hasSize(1);
    }

    /** Someone invited, accepted, and later removed must be able to come back. */
    @Test
    void formerMemberWhoseInvitationWasAccepted_canBeReInvited() throws Exception {
        seedInvitation(REMOVED_EMAIL, InvitationStatus.ACCEPTED, Instant.now().minusSeconds(3600));

        mockMvc.perform(invite(REMOVED_EMAIL, RoleName.MEMBER))
                .andExpect(status().isOk());

        assertThat(pendingInvitationsFor(REMOVED_EMAIL)).hasSize(1);
    }

    /** Case is not a way past the duplicate guard. */
    @Test
    void duplicatePendingInvitation_isRefusedRegardlessOfCase() throws Exception {
        seedInvitation(RETURNING_EMAIL, InvitationStatus.PENDING, Instant.now().plusSeconds(3600));

        mockMvc.perform(invite(RETURNING_EMAIL.toUpperCase(), RoleName.MEMBER))
                .andExpect(status().isConflict());
    }
}
