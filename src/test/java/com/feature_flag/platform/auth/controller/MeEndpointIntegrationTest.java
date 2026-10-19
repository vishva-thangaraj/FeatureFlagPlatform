package com.feature_flag.platform.auth.controller;

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
import org.junit.jupiter.api.DisplayName;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/auth/me} — the call the dashboard shell makes before it can render anything.
 *
 * <p>The access token carries no name, so without this the account menu had nothing to show. The cases
 * that matter are the ones with no usable tenant: this endpoint has to answer them, because the users it
 * cannot answer are precisely the ones who need to be routed somewhere (the tenant picker).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MeEndpointIntegrationTest {

    private static final AtomicInteger CLIENT_ADDRESS_SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private MembershipRepository membershipRepository;

    private Tenant acme;
    private User owner;

    @BeforeEach
    void setUp() {
        acme = tenantRepository.save(Tenant.builder().name("Acme").build());
        owner = userRepository.save(User.builder()
                .email("owner@acme.com").firstName("Jane").lastName("Doe")
                .passwordHash("hash").status(UserStatus.ACTIVE).build());
        saveMembership(owner, RoleName.OWNER, MembershipStatus.ACTIVE);
    }

    @Test
    @DisplayName("returns the identity the token does not carry")
    void returnsIdentityAndRoles() throws Exception {
        mockMvc.perform(as(tokenFor(owner, acme, RoleName.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("owner@acme.com"))
                .andExpect(jsonPath("$.firstName").value("Jane"))
                .andExpect(jsonPath("$.lastName").value("Doe"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.userId").value(String.valueOf(owner.getId())))
                .andExpect(jsonPath("$.tenantId").value(String.valueOf(acme.getId())))
                .andExpect(jsonPath("$.roles[0]").value("OWNER"))
                // An id a browser rounds is an id that addresses nothing -- see TsidJsonContractTest.
                .andExpect(jsonPath("$.userId").isString())
                .andExpect(jsonPath("$.tenantId").isString());
    }

    /** Right after registration there is no tenant at all. The account menu still has to render. */
    @Test
    @DisplayName("answers a user who belongs to no tenant")
    void answersATenantlessUser() throws Exception {
        User drifter = userRepository.save(User.builder()
                .email("drifter@nowhere.com").firstName("Drifter")
                .passwordHash("hash").status(UserStatus.ACTIVE).build());

        mockMvc.perform(as(jwtTokenProvider.generateAccessToken(
                        drifter.getId(), drifter.getEmail(), null, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("drifter@nowhere.com"))
                .andExpect(jsonPath("$.tenantId").doesNotExist())
                .andExpect(jsonPath("$.roles.length()").value(0));
    }

    /**
     * The case that decided the design: a token still naming a tenant the user was just removed from.
     * Answering 403 here would leave them on an error page with no way to reach their other tenants.
     */
    @Test
    @DisplayName("a removed membership reports no current tenant rather than refusing")
    void removedMembershipReportsNoTenant() throws Exception {
        User removed = userRepository.save(User.builder()
                .email("removed@acme.com").firstName("Gone")
                .passwordHash("hash").status(UserStatus.ACTIVE).build());
        saveMembership(removed, RoleName.MEMBER, MembershipStatus.REMOVED);

        mockMvc.perform(as(tokenFor(removed, acme, RoleName.MEMBER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("removed@acme.com"))
                .andExpect(jsonPath("$.tenantId").doesNotExist())
                .andExpect(jsonPath("$.roles.length()").value(0));
    }

    /**
     * Roles come from the membership, not from the claim. A token minted while someone was OWNER must not
     * keep saying OWNER after the role was changed.
     */
    @Test
    @DisplayName("roles are re-read from the membership, not trusted from the token")
    void rolesComeFromTheMembership() throws Exception {
        mockMvc.perform(as(jwtTokenProvider.generateAccessToken(
                        owner.getId(), owner.getEmail(), acme.getId(), List.of("VIEWER", "ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.roles[0]").value("OWNER"));
    }

    /**
     * Being exempt from tenant scoping does not make it public. 403 rather than 401 is this platform's
     * answer to a request with no usable credential -- the same thing every other protected endpoint
     * returns -- not something particular to this one.
     */
    @Test
    @DisplayName("needs a token")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .with(request -> {
                            request.setRemoteAddr("10.9.0." + CLIENT_ADDRESS_SEQUENCE.incrementAndGet());
                            return request;
                        }))
                .andExpect(status().isForbidden());
    }

    private Membership saveMembership(User user, RoleName roleName, MembershipStatus status) {
        Role role = roleRepository.findByName(roleName.name()).orElseThrow();
        return membershipRepository.save(Membership.builder()
                .user(user).tenant(acme).status(status)
                .roles(new HashSet<>(Set.of(role))).build());
    }

    private String tokenFor(User user, Tenant tenant, RoleName roleName) {
        return jwtTokenProvider.generateAccessToken(
                user.getId(), user.getEmail(), tenant.getId(), List.of(roleName.name()));
    }

    private MockHttpServletRequestBuilder as(String token) {
        return get("/api/v1/auth/me")
                .header("Authorization", "Bearer " + token)
                .with(request -> {
                    request.setRemoteAddr("10.9.0." + CLIENT_ADDRESS_SEQUENCE.incrementAndGet());
                    return request;
                });
    }
}
