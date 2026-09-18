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
import com.feature_flag.platform.feature.entity.Env;
import com.feature_flag.platform.feature.entity.Feature;
import com.feature_flag.platform.feature.entity.FeatureEnvStatus;
import com.feature_flag.platform.feature.entity.FeatureRule;
import com.feature_flag.platform.feature.entity.FeatureUserOverride;
import com.feature_flag.platform.feature.enums.RuleOperator;
import com.feature_flag.platform.feature.enums.RuleValueType;
import com.feature_flag.platform.feature.repository.EnvRepository;
import com.feature_flag.platform.feature.repository.FeatureEnvStatusRepository;
import com.feature_flag.platform.feature.repository.FeatureRepository;
import com.feature_flag.platform.feature.repository.FeatureRuleRepository;
import com.feature_flag.platform.feature.repository.FeatureUserOverrideRepository;
import com.feature_flag.platform.tenant.entity.ApiKey;
import com.feature_flag.platform.tenant.entity.Membership;
import com.feature_flag.platform.tenant.entity.Tenant;
import com.feature_flag.platform.tenant.entity.UserInvitation;
import com.feature_flag.platform.tenant.enums.InvitationStatus;
import com.feature_flag.platform.tenant.enums.MembershipStatus;
import com.feature_flag.platform.tenant.repository.ApiKeyRepository;
import com.feature_flag.platform.tenant.repository.MembershipRepository;
import com.feature_flag.platform.tenant.repository.TenantRepository;
import com.feature_flag.platform.tenant.repository.UserInvitationRepository;
import jakarta.persistence.EntityManager;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code DELETE /api/v1/tenants/{tenantId}} used to clean up only {@code memberships} and {@code api_keys},
 * so any tenant that had ever created a feature, an environment or an invitation — or whose owner had ever
 * logged in, which writes a tenant-scoped {@code refresh_tokens} row — died on a foreign-key constraint and
 * came back as a {@code 409} the caller had no way to resolve.
 *
 * <p>These tests pin the three things that matter about the cascade: it <em>completes</em>, it leaves
 * <em>nothing</em> behind in any tenant-owned table, and its blast radius is exactly one tenant.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TenantDeletionCascadeIntegrationTest {

    /** Rate-limit buckets are keyed by client address; a fresh one per request keeps them isolated. */
    private static final AtomicInteger CLIENT_ADDRESS_SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private EntityManager entityManager;

    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private UserInvitationRepository userInvitationRepository;
    @Autowired private FeatureRepository featureRepository;
    @Autowired private EnvRepository envRepository;
    @Autowired private FeatureEnvStatusRepository featureEnvStatusRepository;
    @Autowired private FeatureRuleRepository featureRuleRepository;
    @Autowired private FeatureUserOverrideRepository featureUserOverrideRepository;

    private User owner;
    private User viewer;
    private User stranger;

    /** Fully populated: features, envs, statuses, rules, overrides, invitations, keys, members, sessions. */
    private Tenant loadedTenant;
    /** Equally populated, and must be byte-for-byte untouched by the delete. */
    private Tenant neighbourTenant;
    /** A tenant with a single owner membership and nothing else. */
    private Tenant emptyTenant;

    private String ownerToken;
    private String viewerToken;
    private String strangerToken;

    private Long loadedFeatureAId;
    private Long loadedEnvProdId;

    @BeforeEach
    void setUp() {
        Role ownerRole = roleRepository.findByName(RoleName.OWNER.name()).orElseThrow();
        Role viewerRole = roleRepository.findByName(RoleName.VIEWER.name()).orElseThrow();

        owner = userRepository.save(User.builder()
                .email("cascade-owner@test.com").firstName("Owner").passwordHash("hash")
                .status(UserStatus.ACTIVE).build());
        viewer = userRepository.save(User.builder()
                .email("cascade-viewer@test.com").firstName("Viewer").passwordHash("hash")
                .status(UserStatus.ACTIVE).build());
        stranger = userRepository.save(User.builder()
                .email("cascade-stranger@test.com").firstName("Stranger").passwordHash("hash")
                .status(UserStatus.ACTIVE).build());

        loadedTenant = tenantRepository.save(Tenant.builder().name("Loaded Org").build());
        neighbourTenant = tenantRepository.save(Tenant.builder().name("Neighbour Org").build());
        emptyTenant = tenantRepository.save(Tenant.builder().name("Empty Org").build());

        // The owner owns all three; the viewer is a non-OWNER member of the loaded tenant; the stranger
        // belongs to nothing.
        membership(owner, loadedTenant, ownerRole, MembershipStatus.ACTIVE);
        membership(owner, neighbourTenant, ownerRole, MembershipStatus.ACTIVE);
        membership(owner, emptyTenant, ownerRole, MembershipStatus.ACTIVE);
        membership(viewer, loadedTenant, viewerRole, MembershipStatus.ACTIVE);

        populate(loadedTenant, "loaded");
        populate(neighbourTenant, "neighbour");

        loadedFeatureAId = featureRepository.findByKeyAndTenantId("loaded-flag-a", loadedTenant.getId())
                .orElseThrow().getId();
        loadedEnvProdId = envRepository.findByNameAndTenantId("loaded-prod", loadedTenant.getId())
                .orElseThrow().getId();

        ownerToken = jwtTokenProvider.generateAccessToken(
                owner.getId(), owner.getEmail(), loadedTenant.getId(), List.of(RoleName.OWNER.name()));
        viewerToken = jwtTokenProvider.generateAccessToken(
                viewer.getId(), viewer.getEmail(), loadedTenant.getId(), List.of(RoleName.OWNER.name()));
        strangerToken = jwtTokenProvider.generateAccessToken(
                stranger.getId(), stranger.getEmail(), null, List.of(RoleName.OWNER.name()));
    }

    private void membership(User user, Tenant tenant, Role role, MembershipStatus status) {
        membershipRepository.save(Membership.builder()
                .user(user).tenant(tenant).status(status)
                .roles(new HashSet<>(Set.of(role))).build());
    }

    /**
     * Gives {@code tenant} one of every tenant-owned row: two features x two envs of statuses, each status
     * carrying a rule and a user override, plus invitations, API keys and a live refresh token.
     */
    private void populate(Tenant tenant, String prefix) {
        List<Env> envs = List.of(
                envRepository.save(Env.builder().name(prefix + "-prod").tenant(tenant).build()),
                envRepository.save(Env.builder().name(prefix + "-staging").tenant(tenant).build()));
        List<Feature> features = List.of(
                featureRepository.save(Feature.builder().key(prefix + "-flag-a").tenant(tenant).build()),
                featureRepository.save(Feature.builder().key(prefix + "-flag-b").tenant(tenant).build()));

        for (Feature feature : features) {
            for (Env env : envs) {
                FeatureEnvStatus status = featureEnvStatusRepository.save(FeatureEnvStatus.builder()
                        .feature(feature).env(env).enabled(true).rolloutPercentage(50).build());
                featureRuleRepository.save(FeatureRule.builder()
                        .featureEnvStatus(status).attribute("country")
                        .operator(RuleOperator.IN).valueType(RuleValueType.STRING)
                        .valueJson("[\"IN\"]").priority(1).build());
                featureUserOverrideRepository.save(FeatureUserOverride.builder()
                        .featureEnvStatus(status).externalUserId("user-" + prefix).enabled(true).build());
            }
        }

        userInvitationRepository.save(UserInvitation.builder()
                .email(prefix + "-invitee@test.com").tokenHash(prefix + "-invite-hash")
                .role(RoleName.MEMBER).tenant(tenant).invitedBy(owner)
                .status(InvitationStatus.PENDING).expiresAt(Instant.now().plusSeconds(3600)).build());
        apiKeyRepository.save(ApiKey.builder()
                .tenant(tenant).name(prefix + "-ci").keyHash(prefix + "-key-hash").build());

        // Logging in writes one of these per session, so a real tenant always has some.
        RefreshToken rotated = refreshTokenRepository.save(RefreshToken.builder()
                .user(owner).tenant(tenant).tokenHash(prefix + "-refresh-old")
                .expiresAt(Instant.now().plusSeconds(3600)).revoked(true).build());
        RefreshToken current = refreshTokenRepository.save(RefreshToken.builder()
                .user(owner).tenant(tenant).tokenHash(prefix + "-refresh-new")
                .expiresAt(Instant.now().plusSeconds(3600)).build());
        // Rotation leaves the old row pointing at its replacement: a self-FK inside the same tenant.
        rotated.setReplacedBy(current);
        refreshTokenRepository.save(rotated);
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder, String token) {
        return builder
                .header("Authorization", "Bearer " + token)
                .with(request -> {
                    request.setRemoteAddr("10.2.0." + CLIENT_ADDRESS_SEQUENCE.incrementAndGet());
                    return request;
                });
    }

    // --- Row counting -------------------------------------------------------------------------------

    private long count(String table, String column, Long id) {
        return ((Number) entityManager
                .createNativeQuery("select count(*) from " + table + " where " + column + " = :id")
                .setParameter("id", id)
                .getSingleResult()).longValue();
    }

    /** Every table that holds a direct FK to {@code tenants.id}. */
    private void assertNoRowsOwnedBy(Long tenantId) {
        assertEquals(0, count("feature", "tenant_id", tenantId), "feature rows survived");
        assertEquals(0, count("env", "tenant_id", tenantId), "env rows survived");
        assertEquals(0, count("memberships", "tenant_id", tenantId), "membership rows survived");
        assertEquals(0, count("api_keys", "tenant_id", tenantId), "api key rows survived");
        assertEquals(0, count("user_invitations", "tenant_id", tenantId), "invitation rows survived");
        assertEquals(0, count("refresh_tokens", "tenant_id", tenantId), "refresh token rows survived");
    }

    private long orphanedStatuses() {
        return ((Number) entityManager.createNativeQuery(
                        "select count(*) from feature_env_status s "
                                + "where not exists (select 1 from feature f where f.id = s.feature_id) "
                                + "or not exists (select 1 from env e where e.id = s.env_id)")
                .getSingleResult()).longValue();
    }

    private long orphanedRules() {
        return ((Number) entityManager.createNativeQuery(
                        "select count(*) from feature_rule r where not exists "
                                + "(select 1 from feature_env_status s where s.id = r.feature_env_status_id)")
                .getSingleResult()).longValue();
    }

    private long orphanedOverrides() {
        return ((Number) entityManager.createNativeQuery(
                        "select count(*) from feature_user_override o where not exists "
                                + "(select 1 from feature_env_status s where s.id = o.feature_env_status_id)")
                .getSingleResult()).longValue();
    }

    private long orphanedMembershipRoles() {
        return ((Number) entityManager.createNativeQuery(
                        "select count(*) from membership_roles mr where not exists "
                                + "(select 1 from memberships m where m.id = mr.membership_id)")
                .getSingleResult()).longValue();
    }

    // --- The defect ---------------------------------------------------------------------------------

    /**
     * The headline regression: before the cascade this failed on a foreign-key constraint and surfaced as
     * a 409 the caller could do nothing about, because no endpoint exists to detach the rows first.
     */
    @Test
    void owner_canDeleteATenantThatHasEveryKindOfChildRow() throws Exception {
        Long tenantId = loadedTenant.getId();

        mockMvc.perform(authorized(delete("/api/v1/tenants/" + tenantId), ownerToken))
                .andExpect(status().isNoContent());

        assertFalse(tenantRepository.findById(tenantId).isPresent(), "the tenant row must be gone");
        assertNoRowsOwnedBy(tenantId);
        assertEquals(0, orphanedStatuses(), "feature_env_status rows lost their feature or env");
        assertEquals(0, orphanedRules(), "feature_rule rows lost their feature_env_status");
        assertEquals(0, orphanedOverrides(), "feature_user_override rows lost their feature_env_status");
        assertEquals(0, orphanedMembershipRoles(), "membership_roles rows lost their membership");
    }

    /** The grandchildren specifically: rules and overrides hang off feature_env_status, not off the tenant. */
    @Test
    void deletingATenantRemovesTheWholeFeatureSubtree() throws Exception {
        Long tenantId = loadedTenant.getId();
        assertFalse(featureEnvStatusRepository.findAllByFeatureId(loadedFeatureAId).isEmpty(),
                "precondition: the fixture really has statuses");

        mockMvc.perform(authorized(delete("/api/v1/tenants/" + tenantId), ownerToken))
                .andExpect(status().isNoContent());

        assertTrue(featureRepository.findAllByTenantId(tenantId).isEmpty());
        assertTrue(envRepository.findAllByTenantId(tenantId).isEmpty());
        assertTrue(featureEnvStatusRepository.findAllByFeatureId(loadedFeatureAId).isEmpty());
        assertEquals(0, count("feature_env_status", "env_id", loadedEnvProdId));
    }

    /** A brand-new tenant has no children at all; the cascade must not trip over the empty sets. */
    @Test
    void owner_canDeleteATenantWithNoDataAtAll() throws Exception {
        Long tenantId = emptyTenant.getId();

        mockMvc.perform(authorized(delete("/api/v1/tenants/" + tenantId), ownerToken))
                .andExpect(status().isNoContent());

        assertFalse(tenantRepository.findById(tenantId).isPresent());
        assertNoRowsOwnedBy(tenantId);
    }

    // --- Blast radius -------------------------------------------------------------------------------

    /**
     * The whole point of a multi-tenant system: the neighbour is populated identically, including a
     * feature with the same shape and a membership for the same user, and must come through untouched.
     */
    @Test
    void deletingOneTenantLeavesEveryOtherTenantIntact() throws Exception {
        Long neighbourId = neighbourTenant.getId();

        mockMvc.perform(authorized(delete("/api/v1/tenants/" + loadedTenant.getId()), ownerToken))
                .andExpect(status().isNoContent());

        assertTrue(tenantRepository.findById(neighbourId).isPresent(), "the neighbour tenant must survive");
        assertEquals(2, count("feature", "tenant_id", neighbourId));
        assertEquals(2, count("env", "tenant_id", neighbourId));
        assertEquals(1, count("memberships", "tenant_id", neighbourId));
        assertEquals(1, count("api_keys", "tenant_id", neighbourId));
        assertEquals(1, count("user_invitations", "tenant_id", neighbourId));
        assertEquals(2, count("refresh_tokens", "tenant_id", neighbourId));

        Long neighbourFeatureId = featureRepository
                .findByKeyAndTenantId("neighbour-flag-a", neighbourId).orElseThrow().getId();
        List<FeatureEnvStatus> statuses = featureEnvStatusRepository.findAllByFeatureId(neighbourFeatureId);
        assertEquals(2, statuses.size(), "the neighbour's feature/env statuses must survive");
        for (FeatureEnvStatus status : statuses) {
            assertEquals(1, count("feature_rule", "feature_env_status_id", status.getId()));
            assertEquals(1, count("feature_user_override", "feature_env_status_id", status.getId()));
        }
        // The neighbour's rotated refresh token must still point at its replacement.
        assertEquals(1, ((Number) entityManager.createNativeQuery(
                        "select count(*) from refresh_tokens where replaced_by is not null and tenant_id = :id")
                .setParameter("id", neighbourId).getSingleResult()).longValue());
    }

    // --- Authorization still gates the cascade ------------------------------------------------------

    @Test
    void nonOwnerMember_isRefusedBeforeAnythingIsDeleted() throws Exception {
        Long tenantId = loadedTenant.getId();

        mockMvc.perform(authorized(delete("/api/v1/tenants/" + tenantId), viewerToken))
                .andExpect(status().isForbidden());

        assertTrue(tenantRepository.findById(tenantId).isPresent());
        assertEquals(2, count("feature", "tenant_id", tenantId), "no feature may have been touched");
        assertEquals(2, count("env", "tenant_id", tenantId));
        assertEquals(2, count("memberships", "tenant_id", tenantId));
        assertEquals(1, count("api_keys", "tenant_id", tenantId));
        assertEquals(1, count("user_invitations", "tenant_id", tenantId));
        assertEquals(2, count("refresh_tokens", "tenant_id", tenantId));
    }

    @Test
    void nonMember_isRefusedBeforeAnythingIsDeleted() throws Exception {
        Long tenantId = loadedTenant.getId();

        mockMvc.perform(authorized(delete("/api/v1/tenants/" + tenantId), strangerToken))
                .andExpect(status().isForbidden());

        assertTrue(tenantRepository.findById(tenantId).isPresent());
        assertEquals(2, count("feature", "tenant_id", tenantId));
        assertEquals(2, count("env", "tenant_id", tenantId));
        assertEquals(1, count("api_keys", "tenant_id", tenantId));
    }
}
