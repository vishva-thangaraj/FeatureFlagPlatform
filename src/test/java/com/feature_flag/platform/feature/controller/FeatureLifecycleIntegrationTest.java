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
import com.feature_flag.platform.feature.messaging.FeatureChanged;
import com.feature_flag.platform.feature.repository.EnvRepository;
import com.feature_flag.platform.feature.repository.FeatureEnvStatusRepository;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Write-path behaviour of the feature and environment endpoints: what a request that leaves a field
 * out is taken to mean, and what the API refuses before it reaches the database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@RecordApplicationEvents
@Transactional
class FeatureLifecycleIntegrationTest {

    private static final AtomicInteger CLIENT_ADDRESS_SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private FeatureRepository featureRepository;
    @Autowired private EnvRepository envRepository;
    @Autowired private FeatureEnvStatusRepository featureEnvStatusRepository;

    private String token;
    private Tenant tenant;
    private Env env;
    private Feature feature;

    @BeforeEach
    void setUp() {
        Role ownerRole = roleRepository.findByName(RoleName.OWNER.name()).orElseThrow();
        User user = userRepository.save(User.builder()
                .email("lifecycle@test.com").firstName("Owner").passwordHash("hash")
                .status(UserStatus.ACTIVE).build());
        tenant = tenantRepository.save(Tenant.builder().name("Lifecycle Tenant").build());
        membershipRepository.save(Membership.builder()
                .user(user).tenant(tenant).status(MembershipStatus.ACTIVE)
                .roles(new HashSet<>(Set.of(ownerRole))).build());
        token = jwtTokenProvider.generateAccessToken(
                user.getId(), user.getEmail(), tenant.getId(), List.of(RoleName.OWNER.name()));

        env = envRepository.save(Env.builder().name("prod").tenant(tenant).build());
        feature = featureRepository.save(Feature.builder()
                .key("checkout").description("Checkout rewrite").tenant(tenant).build());
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder) {
        return builder
                .header("Authorization", "Bearer " + token)
                .with(request -> {
                    request.setRemoteAddr("10.2.0." + CLIENT_ADDRESS_SEQUENCE.incrementAndGet());
                    return request;
                });
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return authorized(builder).contentType("application/json").content(body);
    }

    // ------------------------------------------------------------------ enabling a flag

    @Test
    void firstEnableWithoutARolloutPercentage_isFullyRolledOut() throws Exception {
        // rolloutPercentage <= 0 means "off for everyone" to every SDK. A flag the dashboard shows as
        // on must not land in the database meaning the opposite.
        mockMvc.perform(json(post("/api/v1/features/{featureId}/enable", feature.getId()),
                        "{\"envId\":" + env.getId() + ",\"enabled\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(authorized(get("/api/v1/features/{featureId}/envs", feature.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].rolloutPercentage").value(100));
    }

    @Test
    void enableWithAnExplicitRolloutPercentage_storesIt() throws Exception {
        mockMvc.perform(json(post("/api/v1/features/{featureId}/enable", feature.getId()),
                        "{\"envId\":" + env.getId() + ",\"enabled\":true,\"rolloutPercentage\":25}"))
                .andExpect(status().isOk());

        mockMvc.perform(authorized(get("/api/v1/features/{featureId}/envs", feature.getId())))
                .andExpect(jsonPath("$[0].rolloutPercentage").value(25));
    }

    @Test
    void togglingAnExistingFlagWithoutARollout_keepsTheStoredPercentage() throws Exception {
        mockMvc.perform(json(post("/api/v1/features/{featureId}/enable", feature.getId()),
                        "{\"envId\":" + env.getId() + ",\"enabled\":true,\"rolloutPercentage\":25}"))
                .andExpect(status().isOk());

        // A kill-switch toggle carries no percentage; it must not overwrite a deliberate 25%.
        mockMvc.perform(json(post("/api/v1/features/{featureId}/enable", feature.getId()),
                        "{\"envId\":" + env.getId() + ",\"enabled\":false}"))
                .andExpect(status().isOk());

        mockMvc.perform(authorized(get("/api/v1/features/{featureId}/envs", feature.getId())))
                .andExpect(jsonPath("$[0].enabled").value(false))
                .andExpect(jsonPath("$[0].rolloutPercentage").value(25));
    }

    // ------------------------------------------------------------------ partial updates

    @Test
    void updatingAFeatureWithAnEmptyBody_leavesTheDescriptionAlone() throws Exception {
        mockMvc.perform(json(put("/api/v1/features/{featureId}", feature.getId()), "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Checkout rewrite"));
    }

    @Test
    void updatingAFeatureWithAnEmptyString_clearsTheDescription() throws Exception {
        mockMvc.perform(json(put("/api/v1/features/{featureId}", feature.getId()),
                        "{\"description\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value(""));
    }

    @Test
    void updatingAnEnvWithAnEmptyBody_leavesItAlone() throws Exception {
        mockMvc.perform(json(put("/api/v1/envs/{envId}", env.getId()), "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("prod"));
    }

    // ------------------------------------------------------------------ input validation

    @Test
    void featureKeyWithAPathSeparator_isRejected() throws Exception {
        // "checkout/v2" is a legal string but can never match GET /api/v1/config/{featureKey}, and the
        // key is immutable — that flag's config would be unreachable for good.
        mockMvc.perform(json(post("/api/v1/features"),
                        "{\"key\":\"checkout/v2\",\"description\":\"d\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void featureKeyWithSpaces_isRejected() throws Exception {
        mockMvc.perform(json(post("/api/v1/features"), "{\"key\":\"checkout v2\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ordinaryFeatureKey_isAccepted() throws Exception {
        mockMvc.perform(json(post("/api/v1/features"), "{\"key\":\"checkout_v2.new-ui\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void blankEnvName_isRejected() throws Exception {
        mockMvc.perform(json(post("/api/v1/envs"), "{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void overlongEnvName_isRejectedAsABadRequestNotAConstraintViolation() throws Exception {
        String longName = "e".repeat(300);
        mockMvc.perform(json(post("/api/v1/envs"), "{\"name\":\"" + longName + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void blankEnvNameOnUpdate_isRejected() throws Exception {
        mockMvc.perform(json(put("/api/v1/envs/{envId}", env.getId()), "{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ rules

    @Test
    void nullEntryInTheRulesArray_isABadRequestNotAServerError() throws Exception {
        mockMvc.perform(json(post("/api/v1/features/{featureId}/enable", feature.getId()),
                        "{\"envId\":" + env.getId() + ",\"enabled\":true}"))
                .andExpect(status().isOk());

        // Bean Validation cascades into the list but says nothing about a null element, so this used
        // to reach the validator and NPE its way to a 500.
        mockMvc.perform(json(put("/api/v1/features/{featureId}/envs/{envId}/rules",
                        feature.getId(), env.getId()), "{\"rules\":[null]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void twoContainsRulesOnOneAttribute_areAccepted() throws Exception {
        mockMvc.perform(json(post("/api/v1/features/{featureId}/enable", feature.getId()),
                        "{\"envId\":" + env.getId() + ",\"enabled\":true}"))
                .andExpect(status().isOk());

        // "pro" and "beta" can both appear in one string; rejecting this was a false positive, and the
        // advice it came with (use IN) cannot express substring matching at all.
        String body = """
                {"rules":[
                  {"attribute":"name","operator":"CONTAINS","valueType":"STRING","values":["pro"],
                   "caseSensitive":false,"negated":false,"enabled":true,"priority":0},
                  {"attribute":"name","operator":"CONTAINS","valueType":"STRING","values":["beta"],
                   "caseSensitive":false,"negated":false,"enabled":true,"priority":1}
                ]}""";
        mockMvc.perform(json(put("/api/v1/features/{featureId}/envs/{envId}/rules",
                        feature.getId(), env.getId()), body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void contradictoryEqualsRules_areStillRejected() throws Exception {
        mockMvc.perform(json(post("/api/v1/features/{featureId}/enable", feature.getId()),
                        "{\"envId\":" + env.getId() + ",\"enabled\":true}"))
                .andExpect(status().isOk());

        String body = """
                {"rules":[
                  {"attribute":"country","operator":"EQUALS","valueType":"STRING","values":["IN"],
                   "caseSensitive":false,"negated":false,"enabled":true,"priority":0},
                  {"attribute":"country","operator":"EQUALS","valueType":"STRING","values":["US"],
                   "caseSensitive":false,"negated":false,"enabled":true,"priority":1}
                ]}""";
        mockMvc.perform(json(put("/api/v1/features/{featureId}/envs/{envId}/rules",
                        feature.getId(), env.getId()), body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    // ------------------------------------------------------------------ attribute listing

    @Test
    void attributesForAFeatureThatDoesNotExist_is404() throws Exception {
        mockMvc.perform(authorized(get("/api/v1/features/{featureId}/rules/attributes", 999999999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void attributesForAnotherTenantsFeature_is404() throws Exception {
        Tenant other = tenantRepository.save(Tenant.builder().name("Someone Else").build());
        Feature theirs = featureRepository.save(Feature.builder().key("their-flag").tenant(other).build());

        mockMvc.perform(authorized(get("/api/v1/features/{featureId}/rules/attributes", theirs.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void attributesForAFeatureInTheCallersTenant_is200() throws Exception {
        mockMvc.perform(authorized(get("/api/v1/features/{featureId}/rules/attributes", feature.getId())))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ a flag never toggled in this env

    /**
     * The targeting tab opens on exactly this state for every new flag. It used to 404, because the
     * per-environment status row that rules hang off is only created by the enable call.
     */
    @Test
    void rulesAndOverrides_onAnUntouchedFlag_areEmptyNotMissing() throws Exception {
        mockMvc.perform(authorized(get("/api/v1/features/{f}/envs/{e}/rules", feature.getId(), env.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(authorized(
                        get("/api/v1/features/{f}/envs/{e}/overrides", feature.getId(), env.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** An environment that is not this tenant's is still a 404 -- only the config row may be absent. */
    @Test
    void rules_forAnEnvironmentOfAnotherTenant_areNotFound() throws Exception {
        Tenant other = tenantRepository.save(Tenant.builder().name("Other").build());
        Env theirEnv = envRepository.save(Env.builder().name("their-prod").tenant(other).build());

        mockMvc.perform(authorized(
                        get("/api/v1/features/{f}/envs/{e}/rules", feature.getId(), theirEnv.getId())))
                .andExpect(status().isNotFound());
    }

    /**
     * Writing the first rule is itself the act of configuring the flag here, so the row is created on the
     * way -- and crucially without a STATUS_CHANGED event, which is what the dashboard's old workaround
     * (a hidden enable {"enabled": false}) would have emitted.
     */
    @Test
    void addingTheFirstRule_createsTheStatusRowWithoutClaimingAStatusChange(ApplicationEvents events)
            throws Exception {
        mockMvc.perform(json(post("/api/v1/features/{f}/envs/{e}/rules", feature.getId(), env.getId()),
                        """
                        {"attribute":"country","operator":"IN","valueType":"STRING",
                         "values":["IN","US"],"enabled":true,"priority":0}"""))
                .andExpect(status().isCreated());

        var status = featureEnvStatusRepository
                .findByFeatureIdAndEnvId(feature.getId(), env.getId()).orElseThrow();
        assertThat(status.isEnabled()).isFalse();
        // Matches what the enable call creates: the percentage is opted into, never silently 0.
        assertThat(status.getRolloutPercentage()).isEqualTo(100);

        assertThat(events.stream(FeatureChanged.class)
                .map(changed -> changed.event().getChangeType().name()))
                .containsExactly("RULE_ADDED");
    }

    /** The same for the atomic whole-set save the targeting editor actually uses. */
    @Test
    void replacingRulesOnAnUntouchedFlag_createsTheStatusRow() throws Exception {
        mockMvc.perform(json(put("/api/v1/features/{f}/envs/{e}/rules", feature.getId(), env.getId()),
                        """
                        {"rules":[{"attribute":"plan","operator":"EQUALS","valueType":"STRING",
                                   "values":["pro"],"enabled":true,"priority":0}]}"""))
                .andExpect(status().isOk());

        assertThat(featureEnvStatusRepository.findByFeatureIdAndEnvId(feature.getId(), env.getId()))
                .isPresent();
    }

    @Test
    void settingTheFirstOverrideOnAnUntouchedFlag_createsTheStatusRow() throws Exception {
        mockMvc.perform(json(post("/api/v1/features/{f}/envs/{e}/overrides", feature.getId(), env.getId()),
                        "{\"externalUserId\":\"u-123\",\"enabled\":true}"))
                .andExpect(status().isCreated());

        assertThat(featureEnvStatusRepository.findByFeatureIdAndEnvId(feature.getId(), env.getId()))
                .isPresent();
    }
}
