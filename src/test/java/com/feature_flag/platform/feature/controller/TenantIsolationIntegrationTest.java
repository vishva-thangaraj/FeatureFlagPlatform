package com.feature_flag.platform.feature.controller;

import com.feature_flag.platform.auth.entity.Role;
import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.auth.enums.RoleName;
import com.feature_flag.platform.auth.enums.UserStatus;
import com.feature_flag.platform.auth.repository.RoleRepository;
import com.feature_flag.platform.auth.repository.UserRepository;
import com.feature_flag.platform.common.security.JwtTokenProvider;
import com.feature_flag.platform.common.security.filter.TenantContextFilter;
import com.feature_flag.platform.feature.entity.Feature;
import com.feature_flag.platform.feature.repository.FeatureRepository;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end proof that a request only ever reads or writes a tenant the caller belongs to.
 *
 * <p>Before {@code TenantContextFilter}, {@code ?tenantID=} was passed straight through to the service
 * layer, which checked only that the resource belonged to the id supplied — so any authenticated user
 * could read and modify any tenant's flags. These tests pin both halves of the fix: reaching a second
 * tenant you *do* belong to now works, and reaching one you don't is refused.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TenantIsolationIntegrationTest {

    /** Rate-limit buckets are keyed partly by client address; a fresh one per test keeps them isolated. */
    private static final AtomicInteger CLIENT_ADDRESS_SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private FeatureRepository featureRepository;

    private User user;
    private Tenant tenantOne;
    private Tenant tenantTwo;
    private Tenant strangerTenant;
    private String tokenDefaultingToTenantOne;

    @BeforeEach
    void setUp() {
        Role ownerRole = roleRepository.findByName(RoleName.OWNER.name()).orElseThrow();

        user = userRepository.save(User.builder()
                .email("member@test.com").firstName("Member").passwordHash("hash")
                .status(UserStatus.ACTIVE).build());

        tenantOne = tenantRepository.save(Tenant.builder().name("Tenant One").build());
        tenantTwo = tenantRepository.save(Tenant.builder().name("Tenant Two").build());
        // A tenant that exists but which `user` has no membership in at all.
        strangerTenant = tenantRepository.save(Tenant.builder().name("Stranger Tenant").build());

        // Mutable set: these entities stay managed for the whole test, and a later merge has to be able to
        // modify the roles collection.
        membershipRepository.save(Membership.builder()
                .user(user).tenant(tenantOne).status(MembershipStatus.ACTIVE)
                .roles(new HashSet<>(Set.of(ownerRole))).build());
        membershipRepository.save(Membership.builder()
                .user(user).tenant(tenantTwo).status(MembershipStatus.ACTIVE)
                .roles(new HashSet<>(Set.of(ownerRole))).build());

        featureRepository.save(Feature.builder().key("flag-in-tenant-one").tenant(tenantOne).build());
        featureRepository.save(Feature.builder().key("flag-in-tenant-two").tenant(tenantTwo).build());
        featureRepository.save(Feature.builder().key("flag-in-stranger-tenant").tenant(strangerTenant).build());

        // The token names tenant one, exactly as it would after logging in there.
        tokenDefaultingToTenantOne = jwtTokenProvider.generateAccessToken(
                user.getId(), user.getEmail(), tenantOne.getId(), List.of(RoleName.OWNER.name()));
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder) {
        return builder
                .header("Authorization", "Bearer " + tokenDefaultingToTenantOne)
                .with(request -> {
                    request.setRemoteAddr("10.0.0." + CLIENT_ADDRESS_SEQUENCE.incrementAndGet());
                    return request;
                });
    }

    @Test
    void noTenantHeader_usesTheTokensTenant() throws Exception {
        mockMvc.perform(authorized(get("/api/v1/features")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features.length()").value(1))
                .andExpect(jsonPath("$.features[0].key").value("flag-in-tenant-one"));
    }

    @Test
    void tenantHeader_reachesASecondTenantTheUserBelongsTo() throws Exception {
        mockMvc.perform(authorized(get("/api/v1/features"))
                        .header(TenantContextFilter.TENANT_HEADER, tenantTwo.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features.length()").value(1))
                .andExpect(jsonPath("$.features[0].key").value("flag-in-tenant-two"));
    }

    @Test
    void tenantHeader_forATenantWithoutMembership_isForbidden() throws Exception {
        mockMvc.perform(authorized(get("/api/v1/features"))
                        .header(TenantContextFilter.TENANT_HEADER, strangerTenant.getId()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void tenantHeader_thatIsNotNumeric_isBadRequest() throws Exception {
        mockMvc.perform(authorized(get("/api/v1/features"))
                        .header(TenantContextFilter.TENANT_HEADER, "not-a-tenant"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    /**
     * The regression test for the IDOR itself. {@code ?tenantID=} is gone, so supplying it can no longer
     * redirect the request at another tenant — the caller still sees only their token's tenant.
     */
    @Test
    void legacyTenantIdQueryParameter_noLongerRedirectsTheRequest() throws Exception {
        mockMvc.perform(authorized(get("/api/v1/features"))
                        .param("tenantID", String.valueOf(strangerTenant.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features.length()").value(1))
                .andExpect(jsonPath("$.features[0].key").value("flag-in-tenant-one"));
    }

    @Test
    void writesLandInTheHeaderTenant_notTheTokenTenant() throws Exception {
        mockMvc.perform(authorized(post("/api/v1/features"))
                        .header(TenantContextFilter.TENANT_HEADER, tenantTwo.getId())
                        .contentType("application/json")
                        .content("{\"key\":\"created-in-tenant-two\",\"description\":\"d\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(authorized(get("/api/v1/features"))
                        .header(TenantContextFilter.TENANT_HEADER, tenantTwo.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features[*].key",
                        org.hamcrest.Matchers.hasItem("created-in-tenant-two")));

        mockMvc.perform(authorized(get("/api/v1/features")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features[*].key",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("created-in-tenant-two"))));
    }

    @Test
    void writesToATenantWithoutMembership_areForbidden() throws Exception {
        mockMvc.perform(authorized(post("/api/v1/features"))
                        .header(TenantContextFilter.TENANT_HEADER, strangerTenant.getId())
                        .contentType("application/json")
                        .content("{\"key\":\"should-never-exist\",\"description\":\"d\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void removedMembership_losesAccessImmediately() throws Exception {
        Membership membership = membershipRepository
                .findByUserIdAndTenantId(user.getId(), tenantTwo.getId()).orElseThrow();
        membership.setStatus(MembershipStatus.REMOVED);
        membershipRepository.save(membership);

        // The token still names tenant two's roles; authorization no longer comes from the token.
        mockMvc.perform(authorized(get("/api/v1/features"))
                        .header(TenantContextFilter.TENANT_HEADER, tenantTwo.getId()))
                .andExpect(status().isForbidden());
    }
}
