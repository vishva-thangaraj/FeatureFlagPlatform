package com.feature_flag.platform.tenant.controller;

import com.feature_flag.platform.auth.entity.Role;
import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.auth.enums.RoleName;
import com.feature_flag.platform.auth.enums.UserStatus;
import com.feature_flag.platform.auth.repository.RoleRepository;
import com.feature_flag.platform.auth.repository.UserRepository;
import com.feature_flag.platform.common.security.JwtTokenProvider;
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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression tests for privilege escalation through the two endpoints that hand out roles.
 *
 * <p>Neither used to constrain the role being <em>granted</em>. {@code api-security.json} opens both to
 * ADMIN as well as OWNER, so an ADMIN could:
 *
 * <ul>
 *   <li>invite a second address they control with {@code "role":"OWNER"} (or the dormant
 *       {@code "SUPER_ADMIN"}), accept it, and hold a role no endpoint would otherwise give them; or</li>
 *   <li>PUT {@code {"roleName":"OWNER"}} onto any non-owner membership, their own included.
 *       {@code updateMemberRole}'s existing guard refuses to change an <em>existing</em> OWNER's role,
 *       which is a different question entirely.</li>
 * </ul>
 *
 * <p>The rule now enforced: a caller may only grant a role at or below their own rank in the tenant they
 * are acting in, ranked {@code SUPER_ADMIN > OWNER > ADMIN > MEMBER > VIEWER}.
 *
 * <p>{@link #ownerCannotInviteASuperAdmin()} and {@link #ownerCannotPromoteAMemberToSuperAdmin()} are the
 * ones that fail if anybody reaches for {@code RoleName.ordinal()}: SUPER_ADMIN is <em>declared</em> last
 * and so has the highest ordinal of the lot, which an ordinal comparison reads as the lowest privilege.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RoleGrantCeilingIntegrationTest {

    /** Rate-limit buckets are keyed by client address; a fresh one per request keeps them isolated. */
    private static final AtomicInteger CLIENT_ADDRESS_SEQUENCE = new AtomicInteger();

    /** Each invited address must be distinct, or the duplicate-pending-invite guard answers first. */
    private static final AtomicInteger INVITEE_SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserInvitationRepository userInvitationRepository;

    /** Sending is {@code @Async} and would try to reach a real SMTP host; the invitation row is the subject here. */
    @MockitoBean private EmailService emailService;

    private Tenant acme;
    private String ownerToken;
    private String adminToken;
    private Membership adminMembership;
    private Membership memberMembership;

    @BeforeEach
    void setUp() {
        acme = tenantRepository.save(Tenant.builder().name("Acme").build());

        User owner = saveUser("owner@acme.com", "Owner");
        User admin = saveUser("admin@acme.com", "Admin");
        User member = saveUser("member@acme.com", "Member");

        saveMembership(owner, RoleName.OWNER);
        adminMembership = saveMembership(admin, RoleName.ADMIN);
        memberMembership = saveMembership(member, RoleName.MEMBER);

        ownerToken = tokenFor(owner, RoleName.OWNER);
        adminToken = tokenFor(admin, RoleName.ADMIN);
    }

    private User saveUser(String email, String firstName) {
        return userRepository.save(User.builder()
                .email(email).firstName(firstName).passwordHash("hash")
                .status(UserStatus.ACTIVE).build());
    }

    private Membership saveMembership(User user, RoleName roleName) {
        Role role = roleRepository.findByName(roleName.name()).orElseThrow();
        return membershipRepository.save(Membership.builder()
                .user(user).tenant(acme).status(MembershipStatus.ACTIVE)
                .roles(new HashSet<>(Set.of(role))).build());
    }

    private String tokenFor(User user, RoleName roleName) {
        return jwtTokenProvider.generateAccessToken(
                user.getId(), user.getEmail(), acme.getId(), List.of(roleName.name()));
    }

    private MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder builder) {
        return builder
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .with(request -> {
                    request.setRemoteAddr("10.2.0." + CLIENT_ADDRESS_SEQUENCE.incrementAndGet());
                    return request;
                });
    }

    private MockHttpServletRequestBuilder invite(String token, String email, String role) {
        return as(token, post("/api/v1/invitations"))
                .content("{\"email\":\"%s\",\"role\":\"%s\"}".formatted(email, role));
    }

    private MockHttpServletRequestBuilder setRole(String token, Membership membership, String role) {
        return as(token, put("/api/v1/members/{id}/role", membership.getId()))
                .content("{\"roleName\":\"%s\"}".formatted(role));
    }

    private String freshInvitee() {
        return "invitee-" + INVITEE_SEQUENCE.incrementAndGet() + "@corp.com";
    }

    private List<UserInvitation> invitationsFor(String email) {
        return userInvitationRepository.findByEmailIgnoreCaseAndTenantIdAndStatus(
                email, acme.getId(), InvitationStatus.PENDING);
    }

    private Set<String> rolesOf(Membership membership) {
        return membershipRepository.findById(membership.getId()).orElseThrow().getRoles().stream()
                .map(Role::getName).collect(java.util.stream.Collectors.toSet());
    }

    // ------------------------------------------------------------------ invitation: the exploit

    @Test
    void adminCannotInviteAnOwner() throws Exception {
        String invitee = freshInvitee();

        mockMvc.perform(invite(adminToken, invitee, RoleName.OWNER.name()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        assertThat(invitationsFor(invitee))
                .as("no invitation may be persisted for a role the inviter cannot grant")
                .isEmpty();
    }

    @Test
    void adminCannotInviteASuperAdmin() throws Exception {
        String invitee = freshInvitee();

        mockMvc.perform(invite(adminToken, invitee, RoleName.SUPER_ADMIN.name()))
                .andExpect(status().isForbidden());

        assertThat(invitationsFor(invitee)).isEmpty();
    }

    /** The ordinal trap: SUPER_ADMIN is declared last, so an ordinal comparison would wave this through. */
    @Test
    void ownerCannotInviteASuperAdmin() throws Exception {
        String invitee = freshInvitee();

        mockMvc.perform(invite(ownerToken, invitee, RoleName.SUPER_ADMIN.name()))
                .andExpect(status().isForbidden());

        assertThat(invitationsFor(invitee)).isEmpty();
    }

    // ------------------------------------------------------------------ invitation: still works

    @Test
    void adminCanInviteAtOrBelowTheirOwnRank() throws Exception {
        for (RoleName grantable : List.of(RoleName.ADMIN, RoleName.MEMBER, RoleName.VIEWER)) {
            String invitee = freshInvitee();

            mockMvc.perform(invite(adminToken, invitee, grantable.name()))
                    .andExpect(status().isOk());

            assertThat(invitationsFor(invitee))
                    .singleElement()
                    .extracting(UserInvitation::getRole)
                    .isEqualTo(grantable);
        }
    }

    @Test
    void ownerCanInviteAtOrBelowTheirOwnRank() throws Exception {
        for (RoleName grantable : List.of(RoleName.OWNER, RoleName.ADMIN, RoleName.MEMBER, RoleName.VIEWER)) {
            String invitee = freshInvitee();

            mockMvc.perform(invite(ownerToken, invitee, grantable.name()))
                    .andExpect(status().isOk());

            assertThat(invitationsFor(invitee))
                    .singleElement()
                    .extracting(UserInvitation::getRole)
                    .isEqualTo(grantable);
        }
    }

    // ------------------------------------------------------------------ invitation: role name validation

    /**
     * {@code RoleName.valueOf} ran only after a database lookup that MySQL's default collation answers
     * case-insensitively, so this reached {@code valueOf("admin")} and left as an unhandled 500.
     */
    @Test
    void lowercaseRoleOnAnInvitation_isBadRequestNotServerError() throws Exception {
        String invitee = freshInvitee();

        mockMvc.perform(invite(ownerToken, invitee, "admin"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        assertThat(invitationsFor(invitee)).isEmpty();
    }

    @Test
    void unknownRoleOnAnInvitation_isBadRequest() throws Exception {
        mockMvc.perform(invite(ownerToken, freshInvitee(), "ARCHDUKE"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ role change: the exploit

    @Test
    void adminCannotPromoteAMemberToOwner() throws Exception {
        mockMvc.perform(setRole(adminToken, memberMembership, RoleName.OWNER.name()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        assertThat(rolesOf(memberMembership)).containsExactly(RoleName.MEMBER.name());
    }

    /** The self-promotion variant: the member being changed is the caller's own membership. */
    @Test
    void adminCannotPromoteThemselvesToOwner() throws Exception {
        mockMvc.perform(setRole(adminToken, adminMembership, RoleName.OWNER.name()))
                .andExpect(status().isForbidden());

        assertThat(rolesOf(adminMembership)).containsExactly(RoleName.ADMIN.name());
    }

    @Test
    void adminCannotPromoteAMemberToSuperAdmin() throws Exception {
        mockMvc.perform(setRole(adminToken, memberMembership, RoleName.SUPER_ADMIN.name()))
                .andExpect(status().isForbidden());

        assertThat(rolesOf(memberMembership)).containsExactly(RoleName.MEMBER.name());
    }

    /** The ordinal trap again, on the other grant path. */
    @Test
    void ownerCannotPromoteAMemberToSuperAdmin() throws Exception {
        mockMvc.perform(setRole(ownerToken, memberMembership, RoleName.SUPER_ADMIN.name()))
                .andExpect(status().isForbidden());

        assertThat(rolesOf(memberMembership)).containsExactly(RoleName.MEMBER.name());
    }

    // ------------------------------------------------------------------ role change: still works

    @Test
    void ownerCanGrantAdminMemberAndViewer() throws Exception {
        for (RoleName grantable : List.of(RoleName.ADMIN, RoleName.MEMBER, RoleName.VIEWER)) {
            mockMvc.perform(setRole(ownerToken, memberMembership, grantable.name()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.roles[0].name").value(grantable.name()));

            assertThat(rolesOf(memberMembership)).containsExactly(grantable.name());
        }
    }

    @Test
    void adminCanGrantAtOrBelowTheirOwnRank() throws Exception {
        for (RoleName grantable : List.of(RoleName.ADMIN, RoleName.MEMBER, RoleName.VIEWER)) {
            mockMvc.perform(setRole(adminToken, memberMembership, grantable.name()))
                    .andExpect(status().isOk());

            assertThat(rolesOf(memberMembership)).containsExactly(grantable.name());
        }
    }

    /** Unchanged behaviour: an existing OWNER's membership is off limits whoever is asking. */
    @Test
    void ownersRoleStillCannotBeChanged() throws Exception {
        Membership ownerMembership = membershipRepository
                .findByTenantIdAndStatusNot(acme.getId(), MembershipStatus.REMOVED).stream()
                .filter(m -> m.getRoles().stream().anyMatch(r -> r.getName().equals(RoleName.OWNER.name())))
                .findFirst().orElseThrow();

        mockMvc.perform(setRole(ownerToken, ownerMembership, RoleName.ADMIN.name()))
                .andExpect(status().isForbidden());
    }

    @Test
    void lowercaseRoleOnARoleChange_isBadRequestNotServerError() throws Exception {
        mockMvc.perform(setRole(ownerToken, memberMembership, "viewer"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        assertThat(rolesOf(memberMembership)).containsExactly(RoleName.MEMBER.name());
    }
}
