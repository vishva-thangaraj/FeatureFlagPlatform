package com.feature_flag.platform.feature.controller;

import com.feature_flag.platform.auth.entity.Role;
import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.auth.enums.RoleName;
import com.feature_flag.platform.auth.enums.UserStatus;
import com.feature_flag.platform.auth.repository.RoleRepository;
import com.feature_flag.platform.auth.repository.UserRepository;
import com.feature_flag.platform.common.security.ApiKeyGenerator;
import com.feature_flag.platform.common.security.JwtTokenProvider;
import com.feature_flag.platform.common.security.filter.ApiKeyAuthenticationFilter;
import com.feature_flag.platform.common.security.filter.TenantContextFilter;
import com.feature_flag.platform.feature.entity.Env;
import com.feature_flag.platform.feature.entity.Feature;
import com.feature_flag.platform.feature.entity.FeatureEnvStatus;
import com.feature_flag.platform.feature.repository.EnvRepository;
import com.feature_flag.platform.feature.repository.FeatureEnvStatusRepository;
import com.feature_flag.platform.feature.repository.FeatureRepository;
import com.feature_flag.platform.tenant.entity.ApiKey;
import com.feature_flag.platform.tenant.entity.Membership;
import com.feature_flag.platform.tenant.entity.Tenant;
import com.feature_flag.platform.tenant.enums.MembershipStatus;
import com.feature_flag.platform.tenant.repository.ApiKeyRepository;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What an API key may read, end to end through the real filter chain.
 *
 * <p>The reason keys are scoped to an environment rather than to a tenant is here: with a tenant-scoped
 * key and an {@code envName} parameter, a production key shipped inside a customer's application would
 * return the staging configuration — unreleased feature keys and targeting rules naming real users — to
 * anyone who edited a query string.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SdkConfigIntegrationTest {

    private static final AtomicInteger CLIENT_ADDRESS_SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private EnvRepository envRepository;
    @Autowired private FeatureRepository featureRepository;
    @Autowired private FeatureEnvStatusRepository featureEnvStatusRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;

    private Tenant acme;
    private Tenant globex;
    private Env acmeProd;
    private Env acmeStaging;

    private String acmeProdKey;
    private String acmeStagingKey;
    private String globexProdKey;

    @BeforeEach
    void setUp() {
        acme = tenantRepository.save(Tenant.builder().name("Acme").build());
        globex = tenantRepository.save(Tenant.builder().name("Globex").build());

        acmeProd = envRepository.save(Env.builder().name("production").tenant(acme).build());
        acmeStaging = envRepository.save(Env.builder().name("staging").tenant(acme).build());
        Env globexProd = envRepository.save(Env.builder().name("production").tenant(globex).build());

        // "checkout" is live in production. "secret-redesign" exists only in staging: the unreleased
        // work a production key must not be able to enumerate.
        Feature checkout = featureRepository.save(Feature.builder().key("checkout").tenant(acme).build());
        Feature redesign = featureRepository.save(
                Feature.builder().key("secret-redesign").tenant(acme).build());
        Feature globexOnly = featureRepository.save(
                Feature.builder().key("globex-only").tenant(globex).build());

        enableIn(checkout, acmeProd);
        enableIn(redesign, acmeStaging);
        enableIn(globexOnly, globexProd);

        acmeProdKey = issueKey(acme, acmeProd, null, null);
        acmeStagingKey = issueKey(acme, acmeStaging, null, null);
        globexProdKey = issueKey(globex, globexProd, null, null);
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    @DisplayName("a key returns its own environment's flags, with no parameter to supply")
    void keyServesItsOwnEnvironment() throws Exception {
        mockMvc.perform(withKey(get("/api/v1/sdk/config"), acmeProdKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.environment").value("production"))
                .andExpect(jsonPath("$.flags[*].featureKey").value("checkout"));
    }

    @Test
    @DisplayName("a single flag resolves against the key's environment too")
    void singleFlagIsScopedToTheKey() throws Exception {
        mockMvc.perform(withKey(get("/api/v1/sdk/config/checkout"), acmeProdKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.environment").value("production"))
                .andExpect(jsonPath("$.featureKey").value("checkout"));

        // The staging-only flag is not merely hidden from the list; it cannot be addressed directly.
        mockMvc.perform(withKey(get("/api/v1/sdk/config/secret-redesign"), acmeProdKey))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ the isolation this exists for

    @Test
    @DisplayName("a production key cannot reach staging, however envName is supplied")
    void productionKeyCannotReadStaging() throws Exception {
        for (String attempt : List.of("staging", "STAGING", "production")) {
            mockMvc.perform(withKey(get("/api/v1/sdk/config").param("envName", attempt), acmeProdKey))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.environment").value("production"))
                    .andExpect(jsonPath("$.flags[?(@.featureKey == 'secret-redesign')]").isEmpty());
        }

        // And the staging key sees what the production key could not, so the isolation is the key's
        // scope rather than the flag simply being absent everywhere.
        mockMvc.perform(withKey(get("/api/v1/sdk/config"), acmeStagingKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flags[*].featureKey").value("secret-redesign"));
    }

    @Test
    @DisplayName("a key reads only its own tenant")
    void keyCannotCrossTenants() throws Exception {
        mockMvc.perform(withKey(get("/api/v1/sdk/config"), globexProdKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flags[*].featureKey").value("globex-only"));

        mockMvc.perform(withKey(get("/api/v1/sdk/config/checkout"), globexProdKey))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("X-Tenant-Id alongside a key is ignored, not honoured")
    void tenantHeaderCannotRedirectAKey() throws Exception {
        // TenantContextFilter skips this path, so the header has nothing to act on. Were it ever to
        // apply, this is the request that would re-open the cross-tenant hole it was written to close.
        mockMvc.perform(withKey(get("/api/v1/sdk/config"), acmeProdKey)
                        .header(TenantContextFilter.TENANT_HEADER, globex.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flags[*].featureKey").value("checkout"));
    }

    // ------------------------------------------------------------------ refusals

    @Test
    @DisplayName("without a key the SDK surface is closed, and a user's token does not open it")
    void theSurfaceIsKeyOnly() throws Exception {
        mockMvc.perform(unauthenticated(get("/api/v1/sdk/config")))
                .andExpect(status().isForbidden());

        mockMvc.perform(unauthenticated(get("/api/v1/sdk/config"))
                        .header("Authorization", "Bearer " + ownerTokenFor(acme)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a key and a user token together are refused, not silently ranked")
    void ambiguousCredentialsAreRefused() throws Exception {
        mockMvc.perform(withKey(get("/api/v1/sdk/config"), acmeProdKey)
                        .header("Authorization", "Bearer " + ownerTokenFor(acme)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a revoked key stops working immediately, with no cache to wait out")
    void revokedKeyIsRefused() throws Exception {
        String doomed = issueKey(acme, acmeProd, null, null);
        mockMvc.perform(withKey(get("/api/v1/sdk/config"), doomed)).andExpect(status().isOk());

        ApiKey stored = apiKeyRepository.findAll().stream()
                .filter(k -> k.getKeyHash().equals(
                        com.feature_flag.platform.common.security.TokenHashUtil.sha256(doomed)))
                .findFirst().orElseThrow();
        stored.setRevokedAt(Instant.now());
        apiKeyRepository.saveAndFlush(stored);

        mockMvc.perform(withKey(get("/api/v1/sdk/config"), doomed))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an expired key is refused")
    void expiredKeyIsRefused() throws Exception {
        String expired = issueKey(acme, acmeProd, Instant.now().minus(1, ChronoUnit.HOURS), null);

        mockMvc.perform(withKey(get("/api/v1/sdk/config"), expired))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ caching

    @Test
    @DisplayName("the response is revalidatable but never held by a shared cache")
    void cacheHeaders() throws Exception {
        mockMvc.perform(withKey(get("/api/v1/sdk/config"), acmeProdKey))
                .andExpect(status().isOk())
                // no-cache, not no-store: the SDK must keep the body to make a 304 worth anything.
                .andExpect(header().string("Cache-Control", "no-cache, private"))
                .andExpect(header().string("Vary", (String) null));
    }

    @Test
    @DisplayName("a conditional poll is answered 304")
    void conditionalPollIsCheap() throws Exception {
        String etag = mockMvc.perform(withKey(get("/api/v1/sdk/config"), acmeProdKey))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");

        mockMvc.perform(withKey(get("/api/v1/sdk/config"), acmeProdKey).header("If-None-Match", etag))
                .andExpect(status().isNotModified());
    }

    @Test
    @DisplayName("two environments cannot share an ETag, since this URL does not name one")
    void etagDistinguishesEnvironments() throws Exception {
        String prodTag = mockMvc.perform(withKey(get("/api/v1/sdk/config"), acmeProdKey))
                .andReturn().getResponse().getHeader("ETag");
        String stagingTag = mockMvc.perform(withKey(get("/api/v1/sdk/config"), acmeStagingKey))
                .andReturn().getResponse().getHeader("ETag");

        assertThat(prodTag).isNotEqualTo(stagingTag);

        // The staging key presenting production's tag must be answered with staging's config, not 304.
        mockMvc.perform(withKey(get("/api/v1/sdk/config"), acmeStagingKey)
                        .header("If-None-Match", prodTag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.environment").value("staging"));
    }

    // ------------------------------------------------------------------ fixture

    private void enableIn(Feature feature, Env env) {
        featureEnvStatusRepository.save(FeatureEnvStatus.builder()
                .feature(feature).env(env).enabled(true).rolloutPercentage(100).build());
    }

    private String issueKey(Tenant tenant, Env env, Instant expiresAt, Instant revokedAt) {
        ApiKeyGenerator.GeneratedKey generated = ApiKeyGenerator.generate();
        apiKeyRepository.saveAndFlush(ApiKey.builder()
                .tenant(tenant).env(env).name(tenant.getName() + "/" + env.getName())
                .keyHash(generated.keyHash()).prefix(generated.prefix())
                .expiresAt(expiresAt).revokedAt(revokedAt).build());
        return generated.plaintext();
    }

    private String ownerTokenFor(Tenant tenant) {
        Role ownerRole = roleRepository.findByName(RoleName.OWNER.name()).orElseThrow();
        User user = userRepository.save(User.builder()
                .email("owner-" + tenant.getId() + "@test.com").firstName("Owner")
                .passwordHash("hash").status(UserStatus.ACTIVE).build());
        membershipRepository.save(Membership.builder()
                .user(user).tenant(tenant).status(MembershipStatus.ACTIVE)
                .roles(new HashSet<>(Set.of(ownerRole))).build());
        return jwtTokenProvider.generateAccessToken(
                user.getId(), user.getEmail(), tenant.getId(), List.of(RoleName.OWNER.name()));
    }

    private MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder builder, String key) {
        return unauthenticated(builder).header(ApiKeyAuthenticationFilter.API_KEY_HEADER, key);
    }

    /** A fresh source address per request, so the per-IP throttle never decides a test's outcome. */
    private MockHttpServletRequestBuilder unauthenticated(MockHttpServletRequestBuilder builder) {
        return builder.with(request -> {
            request.setRemoteAddr("10.2.0." + (CLIENT_ADDRESS_SEQUENCE.incrementAndGet() % 250 + 1));
            return request;
        });
    }
}
