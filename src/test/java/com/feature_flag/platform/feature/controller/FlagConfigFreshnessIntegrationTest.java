package com.feature_flag.platform.feature.controller;

import com.feature_flag.platform.auth.entity.Role;
import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.auth.enums.RoleName;
import com.feature_flag.platform.auth.enums.UserStatus;
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
import com.feature_flag.platform.tenant.entity.Membership;
import com.feature_flag.platform.tenant.entity.Tenant;
import com.feature_flag.platform.tenant.enums.MembershipStatus;
import com.feature_flag.platform.tenant.repository.MembershipRepository;
import com.feature_flag.platform.tenant.repository.TenantRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The SDK-facing config endpoint is polled with {@code If-None-Match}, so its {@code lastModified} is
 * not cosmetic: it is the only signal that tells a client its cached targeting config is stale.
 *
 * <p>It was computed as a maximum over the rows that still exist, which can only ever move forward.
 * Deleting a rule, an override or a whole flag left it exactly where it was, the ETag never changed,
 * and every polling SDK kept getting {@code 304 Not Modified} — enforcing config that had been
 * deleted, indefinitely. These tests pin the deletion cases specifically, and each one fails against
 * the old computation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FlagConfigFreshnessIntegrationTest {

    private static final AtomicInteger CLIENT_ADDRESS_SEQUENCE = new AtomicInteger();

    /** Everything in the fixture is backdated, so "now" is unambiguously newer than any of it. */
    private static final Instant T1 = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant T5 = Instant.parse("2020-01-05T00:00:00Z");
    private static final Instant T9 = Instant.parse("2020-01-09T00:00:00Z");
    private static final Instant T20 = Instant.parse("2020-01-20T00:00:00Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private FeatureRepository featureRepository;
    @Autowired private EnvRepository envRepository;
    @Autowired private FeatureEnvStatusRepository featureEnvStatusRepository;
    @Autowired private FeatureRuleRepository featureRuleRepository;
    @Autowired private FeatureUserOverrideRepository featureUserOverrideRepository;

    @PersistenceContext private EntityManager em;

    private String token;
    private Tenant tenant;
    private Env env;
    private Feature checkout;
    private Feature billing;
    private FeatureEnvStatus checkoutStatus;
    private FeatureRule olderRule;
    private FeatureRule newerRule;

    @BeforeEach
    void setUp() {
        Role ownerRole = roleRepository.findByName(RoleName.OWNER.name()).orElseThrow();
        User user = userRepository.save(User.builder()
                .email("freshness@test.com").firstName("Poller").passwordHash("hash")
                .status(UserStatus.ACTIVE).build());
        tenant = tenantRepository.save(Tenant.builder().name("Freshness Tenant").build());
        membershipRepository.save(Membership.builder()
                .user(user).tenant(tenant).status(MembershipStatus.ACTIVE)
                .roles(new HashSet<>(Set.of(ownerRole))).build());
        token = jwtTokenProvider.generateAccessToken(
                user.getId(), user.getEmail(), tenant.getId(), List.of(RoleName.OWNER.name()));

        env = envRepository.save(Env.builder().name("prod").tenant(tenant).build());
        checkout = featureRepository.save(Feature.builder().key("checkout").tenant(tenant).build());
        billing = featureRepository.save(Feature.builder().key("billing").tenant(tenant).build());

        checkoutStatus = featureEnvStatusRepository.save(FeatureEnvStatus.builder()
                .feature(checkout).env(env).enabled(true).rolloutPercentage(100).build());
        FeatureEnvStatus billingStatus = featureEnvStatusRepository.save(FeatureEnvStatus.builder()
                .feature(billing).env(env).enabled(true).rolloutPercentage(100).build());

        olderRule = featureRuleRepository.save(rule(checkoutStatus, "country"));
        newerRule = featureRuleRepository.save(rule(checkoutStatus, "plan"));

        // fes at T1, rules at T5 and T9: the scenario where the flag's lastModified is pinned by the
        // newest rule, so deleting the older one is invisible to a maximum-over-survivors.
        backdate("Env", env.getId(), T1);
        backdate("FeatureEnvStatus", checkoutStatus.getId(), T1);
        backdate("FeatureEnvStatus", billingStatus.getId(), T20);
        backdate("FeatureRule", olderRule.getId(), T5);
        backdate("FeatureRule", newerRule.getId(), T9);
        em.flush();
        em.clear();
    }

    private FeatureRule rule(FeatureEnvStatus status, String attribute) {
        return FeatureRule.builder()
                .featureEnvStatus(status)
                .attribute(attribute)
                .operator(RuleOperator.IN)
                .valueType(RuleValueType.STRING)
                .valueJson("[\"US\"]")
                .priority(0)
                .build();
    }

    /** JPQL bulk update: it writes the column without Hibernate's {@code @UpdateTimestamp} intervening. */
    private void backdate(String entity, Long id, Instant when) {
        em.createQuery("update " + entity + " e set e.updatedAt = :when where e.id = :id")
                .setParameter("when", when)
                .setParameter("id", id)
                .executeUpdate();
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder) {
        return builder
                .header("Authorization", "Bearer " + token)
                .with(request -> {
                    request.setRemoteAddr("10.1.0." + CLIENT_ADDRESS_SEQUENCE.incrementAndGet());
                    return request;
                });
    }

    private String etagOfList() throws Exception {
        return mockMvc.perform(authorized(get("/api/v1/config").param("envName", "prod")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");
    }

    // ------------------------------------------------------------------ deletions must invalidate

    @Test
    void deletingANonNewestRule_changesTheEtagAndEndsThe304() throws Exception {
        String etag = etagOfList();
        assertThat(etag).isNotNull();

        // Baseline: nothing changed yet, so the conditional poll is correctly answered 304.
        mockMvc.perform(authorized(get("/api/v1/config").param("envName", "prod"))
                        .header("If-None-Match", etag))
                .andExpect(status().isNotModified());

        mockMvc.perform(authorized(delete("/api/v1/features/{featureId}/rules/{ruleId}",
                        checkout.getId(), olderRule.getId())))
                .andExpect(status().isNoContent());

        // The payload lost a rule, so the very same conditional request must not be 304 any more.
        String refreshed = mockMvc.perform(authorized(get("/api/v1/config").param("envName", "prod"))
                        .header("If-None-Match", etag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flags[?(@.featureKey=='checkout')].rules.length()").value(1))
                .andReturn().getResponse().getHeader("ETag");

        assertThat(refreshed).isNotEqualTo(etag);
    }

    @Test
    void deletingANonNewestRule_changesTheSingleFlagEtagToo() throws Exception {
        String etag = mockMvc.perform(authorized(get("/api/v1/config/checkout").param("envName", "prod")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rules.length()").value(2))
                .andReturn().getResponse().getHeader("ETag");

        mockMvc.perform(authorized(delete("/api/v1/features/{featureId}/rules/{ruleId}",
                        checkout.getId(), olderRule.getId())))
                .andExpect(status().isNoContent());

        mockMvc.perform(authorized(get("/api/v1/config/checkout").param("envName", "prod"))
                        .header("If-None-Match", etag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rules.length()").value(1));
    }

    @Test
    void removingAUserFromTheAllowList_changesTheEtag() throws Exception {
        FeatureUserOverride override = featureUserOverrideRepository.save(FeatureUserOverride.builder()
                .featureEnvStatus(em.getReference(FeatureEnvStatus.class, checkoutStatus.getId()))
                .externalUserId("u-1")
                .enabled(true)
                .build());
        backdate("FeatureUserOverride", override.getId(), T5);
        em.flush();
        em.clear();

        String etag = etagOfList();
        mockMvc.perform(authorized(get("/api/v1/config").param("envName", "prod"))
                        .header("If-None-Match", etag))
                .andExpect(status().isNotModified());

        mockMvc.perform(authorized(delete("/api/v1/features/{featureId}/envs/{envId}/overrides/{userId}",
                        checkout.getId(), env.getId(), "u-1")))
                .andExpect(status().isNoContent());

        String refreshed = mockMvc.perform(authorized(get("/api/v1/config").param("envName", "prod"))
                        .header("If-None-Match", etag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flags[?(@.featureKey=='checkout')].enabledUsers.length()").value(0))
                .andReturn().getResponse().getHeader("ETag");

        assertThat(refreshed).isNotEqualTo(etag);
    }

    @Test
    void deletingAFeatureThatIsNotTheNewest_changesTheListEtag() throws Exception {
        String etag = mockMvc.perform(authorized(get("/api/v1/config").param("envName", "prod")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flags.length()").value(2))
                .andReturn().getResponse().getHeader("ETag");

        // 'billing' is the most recently touched row, so removing 'checkout' cannot move a maximum
        // taken over the rows that survive.
        mockMvc.perform(authorized(delete("/api/v1/features/{featureId}", checkout.getId())))
                .andExpect(status().isNoContent());

        String refreshed = mockMvc.perform(authorized(get("/api/v1/config").param("envName", "prod"))
                        .header("If-None-Match", etag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flags.length()").value(1))
                .andExpect(jsonPath("$.flags[0].featureKey").value("billing"))
                .andReturn().getResponse().getHeader("ETag");

        assertThat(refreshed).isNotEqualTo(etag);
    }

    // ------------------------------------------------------------------ unknown environment

    @Test
    void unknownEnvName_is404RatherThanAnEmptyPayload() throws Exception {
        mockMvc.perform(authorized(get("/api/v1/config").param("envName", "prd")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void knownEnvNameWithNoFlags_isStillAnEmpty200() throws Exception {
        envRepository.save(Env.builder().name("staging").tenant(tenant).build());
        em.flush();
        em.clear();

        mockMvc.perform(authorized(get("/api/v1/config").param("envName", "staging")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flags.length()").value(0));
    }

    @Test
    void anotherTenantsEnvName_is404() throws Exception {
        Tenant other = tenantRepository.save(Tenant.builder().name("Other Tenant").build());
        envRepository.save(Env.builder().name("their-env").tenant(other).build());
        em.flush();
        em.clear();

        mockMvc.perform(authorized(get("/api/v1/config").param("envName", "their-env")))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ RFC 7232 ETag comparison

    @Test
    void weakenedEtag_stillMatches() throws Exception {
        String etag = etagOfList();

        // A proxy that compresses the response weakens the validator. Strict equality dropped the
        // 304 path entirely for every client behind one.
        mockMvc.perform(authorized(get("/api/v1/config").param("envName", "prod"))
                        .header("If-None-Match", "W/" + etag))
                .andExpect(status().isNotModified());
    }

    @Test
    void multiValuedIfNoneMatch_matchesOnAnyMember() throws Exception {
        String etag = etagOfList();

        mockMvc.perform(authorized(get("/api/v1/config").param("envName", "prod"))
                        .header("If-None-Match", "\"stale-one\", " + etag + ", \"stale-two\""))
                .andExpect(status().isNotModified());
    }

    @Test
    void wildcardIfNoneMatch_matches() throws Exception {
        mockMvc.perform(authorized(get("/api/v1/config").param("envName", "prod"))
                        .header("If-None-Match", "*"))
                .andExpect(status().isNotModified());
    }

    @Test
    void unrelatedEtag_doesNotMatch() throws Exception {
        mockMvc.perform(authorized(get("/api/v1/config").param("envName", "prod"))
                        .header("If-None-Match", "\"999-1\""))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ environment deletion

    @Test
    void deletingAnEnvironmentWithConfiguredFlags_succeedsAndRemovesItsConfig() throws Exception {
        // Before: FeatureEnvStatus.env has no cascade and no inverse collection, so this hit an FK
        // violation — a 500 originally, a 409 after Wave 1, and no way for the caller to resolve it.
        mockMvc.perform(authorized(delete("/api/v1/envs/{envId}", env.getId())))
                .andExpect(status().isNoContent());

        mockMvc.perform(authorized(get("/api/v1/config").param("envName", "prod")))
                .andExpect(status().isNotFound());

        // The features themselves are untouched; only their configuration in this env is gone.
        mockMvc.perform(authorized(get("/api/v1/features/{featureId}", billing.getId())))
                .andExpect(status().isOk());
        mockMvc.perform(authorized(get("/api/v1/features/{featureId}/envs", checkout.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
