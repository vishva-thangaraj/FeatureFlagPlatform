package com.feature_flag.platform.common.security.filter;

import com.feature_flag.platform.auth.entity.Role;
import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.auth.enums.RoleName;
import com.feature_flag.platform.auth.enums.UserStatus;
import com.feature_flag.platform.auth.repository.RoleRepository;
import com.feature_flag.platform.auth.repository.UserRepository;
import com.feature_flag.platform.common.security.JwtTokenProvider;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end proof that the {@code roles} declared in {@code api-security.json} are actually enforced by
 * {@code RoleAuthorizationFilter}, through the real security filter chain.
 *
 * <p>Nothing proved this before. The filter matched its rules against {@code request.getServletPath()},
 * which returns the full path only because the dispatcher servlet is mapped at {@code /} and is
 * <em>empty</em> for any request that did not go through a servlet mapping — which is every request under
 * MockMvc, where the path lives in {@code getRequestURI()}. So in every integration test the filter looked
 * up the rule for {@code ""}, found none, and waved the request straight through. The role rules looked
 * covered while being completely dead under test; only production exercised them.
 *
 * <p>Each assertion below pins the <em>message</em> {@code RoleAuthorizationFilter} writes and nothing else
 * does, so a 403 that happens to come from a service-layer check somewhere further down cannot be mistaken
 * for the declarative rule doing its job.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DeclarativeRoleEnforcementIntegrationTest {

    /** The one message RoleAuthorizationFilter writes when a principal lacks the declared role. */
    private static final String ROLE_DENIED = "You do not have the required role to access this resource.";

    private static final AtomicInteger CLIENT_ADDRESS_SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private RoleRepository roleRepository;

    private Tenant tenant;

    /** A real token for a VIEWER of {@link #tenant}, exactly as login would mint it. */
    private String viewerToken;

    /** A real token for the OWNER of the same tenant. */
    private String ownerToken;

    /**
     * A VIEWER's token that <em>claims</em> OWNER. The roles that decide are the ones resolved from the
     * membership, never the ones carried in the token.
     */
    private String viewerTokenClaimingOwner;

    @BeforeEach
    void setUp() {
        Role ownerRole = roleRepository.findByName(RoleName.OWNER.name()).orElseThrow();
        Role viewerRole = roleRepository.findByName(RoleName.VIEWER.name()).orElseThrow();

        tenant = tenantRepository.save(Tenant.builder().name("Role Rules Org").build());

        User viewer = userRepository.save(User.builder()
                .email("viewer@roles.test").firstName("Viewer").passwordHash("hash")
                .status(UserStatus.ACTIVE).build());
        User owner = userRepository.save(User.builder()
                .email("owner@roles.test").firstName("Owner").passwordHash("hash")
                .status(UserStatus.ACTIVE).build());

        membershipRepository.save(Membership.builder()
                .user(viewer).tenant(tenant).status(MembershipStatus.ACTIVE)
                .roles(new HashSet<>(Set.of(viewerRole))).build());
        membershipRepository.save(Membership.builder()
                .user(owner).tenant(tenant).status(MembershipStatus.ACTIVE)
                .roles(new HashSet<>(Set.of(ownerRole))).build());

        viewerToken = jwtTokenProvider.generateAccessToken(
                viewer.getId(), viewer.getEmail(), tenant.getId(), List.of(RoleName.VIEWER.name()));
        ownerToken = jwtTokenProvider.generateAccessToken(
                owner.getId(), owner.getEmail(), tenant.getId(), List.of(RoleName.OWNER.name()));
        viewerTokenClaimingOwner = jwtTokenProvider.generateAccessToken(
                viewer.getId(), viewer.getEmail(), tenant.getId(), List.of(RoleName.OWNER.name()));
    }

    private MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder builder) {
        return builder
                .header("Authorization", "Bearer " + token)
                .with(request -> {
                    request.setRemoteAddr("10.9.0." + CLIENT_ADDRESS_SEQUENCE.incrementAndGet());
                    return request;
                });
    }

    // --- the rule is enforced -----------------------------------------------------------------------

    /**
     * {@code POST /api/v1/features} declares {@code "roles": ["OWNER", "ADMIN"]}. A VIEWER must be stopped
     * by that declaration, in the filter chain, before the controller runs.
     */
    @Test
    void viewer_isRefusedAnEndpointDeclaringOwnerOrAdmin() throws Exception {
        mockMvc.perform(as(viewerToken, post("/api/v1/features"))
                        .contentType("application/json")
                        .content("{\"key\":\"viewer-should-not-create-this\",\"description\":\"d\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value(ROLE_DENIED));
    }

    /** The same rule on a pattern path with two wildcards, so pattern matching is exercised too. */
    @Test
    void viewer_isRefusedAPatternPathDeclaringOwnerOrAdmin() throws Exception {
        mockMvc.perform(as(viewerToken, post("/api/v1/features/1/envs/1/rules"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(ROLE_DENIED));
    }

    /**
     * The roles that count come from the membership {@code TenantContextFilter} resolved, not from the
     * token. A forged-looking claim of OWNER on a VIEWER's membership changes nothing.
     */
    @Test
    void tokenClaimingARoleTheMembershipDoesNotGrant_isStillRefused() throws Exception {
        mockMvc.perform(as(viewerTokenClaimingOwner, post("/api/v1/features"))
                        .contentType("application/json")
                        .content("{\"key\":\"claimed-owner\",\"description\":\"d\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(ROLE_DENIED));
    }

    // --- and it is not over-applied -----------------------------------------------------------------

    /** {@code GET /api/v1/features} declares {@code "roles": []}: any member may read. */
    @Test
    void viewer_isAllowedAnEndpointDeclaringNoRoles() throws Exception {
        mockMvc.perform(as(viewerToken, get("/api/v1/features")))
                .andExpect(status().isOk());
    }

    /** The holder of the declared role still gets through — the rule denies, it does not block. */
    @Test
    void owner_isAllowedTheEndpointDeclaringOwnerOrAdmin() throws Exception {
        mockMvc.perform(as(ownerToken, post("/api/v1/features"))
                        .contentType("application/json")
                        .content("{\"key\":\"owner-may-create-this\",\"description\":\"d\"}"))
                .andExpect(status().isCreated());
    }
}
