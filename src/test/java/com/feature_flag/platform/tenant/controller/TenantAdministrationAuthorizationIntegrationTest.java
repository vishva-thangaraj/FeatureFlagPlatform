package com.feature_flag.platform.tenant.controller;

import com.feature_flag.platform.auth.entity.Role;
import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.auth.enums.RoleName;
import com.feature_flag.platform.auth.enums.UserStatus;
import com.feature_flag.platform.auth.repository.RoleRepository;
import com.feature_flag.platform.auth.repository.UserRepository;
import com.feature_flag.platform.common.security.JwtTokenProvider;
import com.feature_flag.platform.common.security.filter.TenantContextFilter;
import com.feature_flag.platform.tenant.entity.Membership;
import com.feature_flag.platform.tenant.entity.Tenant;
import com.feature_flag.platform.tenant.enums.MembershipStatus;
import com.feature_flag.platform.tenant.repository.MembershipRepository;
import com.feature_flag.platform.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end proof that {@code /api/v1/tenants/{tenantId}} authorizes against the tenant in the
 * <em>path</em>.
 *
 * <p>These endpoints were the one hole left by {@code TenantContextFilter}: the filter skipped every path
 * under {@code /api/v1/tenants/}, so the {@code OWNER} requirement that {@code api-security.json} declares
 * for PUT and DELETE was checked against the roles baked into the caller's JWT — the roles in the caller's
 * <em>own default</em> tenant. Creating a tenant makes you its OWNER, so every registered user carried
 * {@code roles:["OWNER"]} and could rename or delete any tenant on the platform by id;
 * {@code deleteTenant} itself looked up nothing but the tenant row. The tests below pin every half of the
 * fix: the path tenant decides both the membership and the roles, and the legitimate owner still works.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TenantAdministrationAuthorizationIntegrationTest {

    /** Rate-limit buckets are keyed by client address; a fresh one per request keeps them isolated. */
    private static final AtomicInteger CLIENT_ADDRESS_SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private RoleRepository roleRepository;

    private User attacker;
    private Tenant ownTenant;
    private Tenant victimTenant;
    private Tenant viewerTenant;
    private Tenant formerTenant;

    /** The attacker's real token: OWNER, in their own tenant, exactly as login would mint it. */
    private String attackerToken;

    @BeforeEach
    void setUp() {
        Role ownerRole = roleRepository.findByName(RoleName.OWNER.name()).orElseThrow();
        Role viewerRole = roleRepository.findByName(RoleName.VIEWER.name()).orElseThrow();

        attacker = userRepository.save(User.builder()
                .email("attacker@test.com").firstName("Attacker").passwordHash("hash")
                .status(UserStatus.ACTIVE).build());
        User victim = userRepository.save(User.builder()
                .email("victim@test.com").firstName("Victim").passwordHash("hash")
                .status(UserStatus.ACTIVE).build());

        ownTenant = tenantRepository.save(Tenant.builder().name("Attacker Org").build());
        victimTenant = tenantRepository.save(Tenant.builder().name("Victim Org").build());
        viewerTenant = tenantRepository.save(Tenant.builder().name("Viewer Org").build());
        formerTenant = tenantRepository.save(Tenant.builder().name("Former Org").build());

        // Created their own tenant, so: OWNER there, and OWNER in the token.
        membershipRepository.save(Membership.builder()
                .user(attacker).tenant(ownTenant).status(MembershipStatus.ACTIVE)
                .roles(new HashSet<>(Set.of(ownerRole))).build());
        // Belongs to a second tenant, but only as a VIEWER.
        membershipRepository.save(Membership.builder()
                .user(attacker).tenant(viewerTenant).status(MembershipStatus.ACTIVE)
                .roles(new HashSet<>(Set.of(viewerRole))).build());
        // Used to belong to a third; the membership row survives, the access must not.
        membershipRepository.save(Membership.builder()
                .user(attacker).tenant(formerTenant).status(MembershipStatus.REMOVED)
                .roles(new HashSet<>(Set.of(ownerRole))).build());
        // No membership at all in the victim's tenant.
        membershipRepository.save(Membership.builder()
                .user(victim).tenant(victimTenant).status(MembershipStatus.ACTIVE)
                .roles(new HashSet<>(Set.of(ownerRole))).build());

        attackerToken = jwtTokenProvider.generateAccessToken(
                attacker.getId(), attacker.getEmail(), ownTenant.getId(), List.of(RoleName.OWNER.name()));
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder) {
        return builder
                .header("Authorization", "Bearer " + attackerToken)
                .with(request -> {
                    request.setRemoteAddr("10.1.0." + CLIENT_ADDRESS_SEQUENCE.incrementAndGet());
                    return request;
                });
    }

    // --- The exploit ------------------------------------------------------------------------------

    /**
     * The headline vulnerability: OWNER of their own tenant, so the JWT says OWNER, so the role filter
     * waved the request through and {@code deleteTenant} dropped the row without ever asking who was
     * calling.
     */
    @Test
    void ownerOfAnotherTenant_cannotDeleteATenantTheyDoNotBelongTo() throws Exception {
        mockMvc.perform(authorized(delete("/api/v1/tenants/" + victimTenant.getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        assertTrue(tenantRepository.findById(victimTenant.getId()).isPresent(),
                "the victim's tenant must survive");
    }

    @Test
    void ownerOfAnotherTenant_cannotRenameATenantTheyDoNotBelongTo() throws Exception {
        mockMvc.perform(authorized(put("/api/v1/tenants/" + victimTenant.getId()))
                        .contentType("application/json")
                        .content("{\"name\":\"Hijacked\"}"))
                .andExpect(status().isForbidden());

        assertEquals("Victim Org", tenantRepository.findById(victimTenant.getId()).orElseThrow().getName());
    }

    @Test
    void ownerOfAnotherTenant_cannotReadATenantTheyDoNotBelongTo() throws Exception {
        mockMvc.perform(authorized(get("/api/v1/tenants/" + victimTenant.getId())))
                .andExpect(status().isForbidden());
    }

    /**
     * A member of the target tenant, but only a VIEWER there. The role that matters is the one held in the
     * tenant being modified, not the OWNER carried in the token from somewhere else.
     */
    @Test
    void nonOwnerMember_cannotRenameTheTenant() throws Exception {
        mockMvc.perform(authorized(put("/api/v1/tenants/" + viewerTenant.getId()))
                        .contentType("application/json")
                        .content("{\"name\":\"Renamed By Viewer\"}"))
                .andExpect(status().isForbidden());

        assertEquals("Viewer Org", tenantRepository.findById(viewerTenant.getId()).orElseThrow().getName());
    }

    @Test
    void nonOwnerMember_cannotDeleteTheTenant() throws Exception {
        mockMvc.perform(authorized(delete("/api/v1/tenants/" + viewerTenant.getId())))
                .andExpect(status().isForbidden());

        assertTrue(tenantRepository.findById(viewerTenant.getId()).isPresent());
    }

    /** A VIEWER may still read the tenant they belong to. */
    @Test
    void nonOwnerMember_canStillReadTheTenant() throws Exception {
        mockMvc.perform(authorized(get("/api/v1/tenants/" + viewerTenant.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Viewer Org"))
                .andExpect(jsonPath("$.roles[0].name").value(RoleName.VIEWER.name()));
    }

    /** The membership row is still there with OWNER on it; REMOVED means REMOVED. */
    @Test
    void removedMember_isForbiddenOnGet() throws Exception {
        mockMvc.perform(authorized(get("/api/v1/tenants/" + formerTenant.getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void removedMember_isForbiddenOnDelete() throws Exception {
        mockMvc.perform(authorized(delete("/api/v1/tenants/" + formerTenant.getId())))
                .andExpect(status().isForbidden());

        assertTrue(tenantRepository.findById(formerTenant.getId()).isPresent());
    }

    /** The header cannot be used to steer these endpoints either — the path is the tenant. */
    @Test
    void tenantHeader_cannotRedirectThePathTenant() throws Exception {
        mockMvc.perform(authorized(get("/api/v1/tenants/" + victimTenant.getId()))
                        .header(TenantContextFilter.TENANT_HEADER, ownTenant.getId()))
                .andExpect(status().isForbidden());
    }

    // --- The legitimate owner still works ---------------------------------------------------------

    @Test
    void owner_canReadTheirOwnTenant() throws Exception {
        mockMvc.perform(authorized(get("/api/v1/tenants/" + ownTenant.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(String.valueOf(ownTenant.getId())))
                .andExpect(jsonPath("$.name").value("Attacker Org"))
                .andExpect(jsonPath("$.roles[0].name").value(RoleName.OWNER.name()));
    }

    @Test
    void owner_canRenameTheirOwnTenant() throws Exception {
        mockMvc.perform(authorized(put("/api/v1/tenants/" + ownTenant.getId()))
                        .contentType("application/json")
                        .content("{\"name\":\"Attacker Org Renamed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Attacker Org Renamed"));

        assertEquals("Attacker Org Renamed",
                tenantRepository.findById(ownTenant.getId()).orElseThrow().getName());
    }

    @Test
    void owner_canDeleteTheirOwnTenant() throws Exception {
        mockMvc.perform(authorized(delete("/api/v1/tenants/" + ownTenant.getId())))
                .andExpect(status().isNoContent());

        assertFalse(tenantRepository.findById(ownTenant.getId()).isPresent());
        assertTrue(membershipRepository.findByTenantId(ownTenant.getId()).isEmpty(),
                "the tenant's memberships hold FKs to it and must go with it");
    }

    @Test
    void nonNumericTenantInThePath_isBadRequest() throws Exception {
        mockMvc.perform(authorized(get("/api/v1/tenants/not-a-tenant")))
                .andExpect(status().isBadRequest());
    }
}
