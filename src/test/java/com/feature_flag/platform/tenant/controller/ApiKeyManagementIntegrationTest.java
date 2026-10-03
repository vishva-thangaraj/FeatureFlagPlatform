package com.feature_flag.platform.tenant.controller;

import com.feature_flag.platform.auth.entity.Role;
import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.auth.enums.RoleName;
import com.feature_flag.platform.auth.enums.UserStatus;
import com.feature_flag.platform.auth.repository.RoleRepository;
import com.feature_flag.platform.auth.repository.UserRepository;
import com.feature_flag.platform.common.security.JwtTokenProvider;
import com.feature_flag.platform.common.security.TokenHashUtil;
import com.feature_flag.platform.common.security.filter.ApiKeyAuthenticationFilter;
import com.feature_flag.platform.feature.entity.Env;
import com.feature_flag.platform.feature.repository.EnvRepository;
import com.feature_flag.platform.tenant.entity.ApiKey;
import com.feature_flag.platform.tenant.entity.Membership;
import com.feature_flag.platform.tenant.entity.Tenant;
import com.feature_flag.platform.tenant.enums.MembershipStatus;
import com.feature_flag.platform.tenant.repository.ApiKeyRepository;
import com.feature_flag.platform.tenant.repository.MembershipRepository;
import com.feature_flag.platform.tenant.repository.TenantRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issuing, listing and revoking keys from the dashboard, and what the issued key can then do.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ApiKeyManagementIntegrationTest {

    private static final AtomicInteger CLIENT_ADDRESS_SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private EnvRepository envRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;

    private Tenant acme;
    private Tenant globex;
    private String ownerToken;
    private String memberToken;

    @BeforeEach
    void setUp() {
        acme = tenantRepository.save(Tenant.builder().name("Acme").build());
        globex = tenantRepository.save(Tenant.builder().name("Globex").build());
        envRepository.save(Env.builder().name("production").tenant(acme).build());
        envRepository.save(Env.builder().name("gx-production").tenant(globex).build());

        ownerToken = tokenFor(acme, RoleName.OWNER, "owner");
        memberToken = tokenFor(acme, RoleName.MEMBER, "member");
    }

    // ------------------------------------------------------------------ issuing

    @Test
    @DisplayName("creation returns the key once; what is stored is its digest")
    void createReturnsThePlaintextOnlyOnce() throws Exception {
        JsonNode created = create(ownerToken, body("ci", "production", null), 201);

        String plaintext = created.get("key").asText();
        assertThat(plaintext).startsWith("ffsdk_");
        assertThat(created.at("/apiKey/envName").asText()).isEqualTo("production");
        assertThat(created.at("/apiKey/prefix").asText()).isEqualTo(plaintext.substring(0, 12));

        ApiKey stored = apiKeyRepository.findAll().get(0);
        assertThat(stored.getKeyHash()).isEqualTo(TokenHashUtil.sha256(plaintext));
        assertThat(stored.getKeyHash()).isNotEqualTo(plaintext);

        // The listing is the only way back to a key, and it does not carry one.
        String listing = mockMvc.perform(authorized(get("/api/v1/api-keys"), ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeys[0].prefix").value(plaintext.substring(0, 12)))
                .andReturn().getResponse().getContentAsString();
        assertThat(listing).doesNotContain(plaintext);
    }

    @Test
    @DisplayName("an issued key works against the SDK surface straight away")
    void issuedKeyAuthenticates() throws Exception {
        String key = create(ownerToken, body("sdk", "production", null), 201).get("key").asText();

        mockMvc.perform(withKey(get("/api/v1/sdk/config"), key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.environment").value("production"));
    }

    @Test
    @DisplayName("a key cannot be issued for an environment the tenant does not have")
    void environmentMustBelongToTheTenant() throws Exception {
        create(ownerToken, body("typo", "prod", null), 404);

        // Another tenant's environment is not "not yours", it is simply not found: the lookup is
        // scoped, so a caller cannot learn whether the name exists elsewhere.
        create(ownerToken, body("theft", "gx-production", null), 404);
    }

    @Test
    @DisplayName("an expiry already in the past is refused rather than stored dead on arrival")
    void expiryMustBeInTheFuture() throws Exception {
        create(ownerToken, body("stale", "production",
                Instant.now().minus(1, ChronoUnit.MINUTES)), 400);

        JsonNode ok = create(ownerToken, body("fresh", "production",
                Instant.now().plus(30, ChronoUnit.DAYS)), 201);
        assertThat(ok.at("/apiKey/expiresAt").asText()).isNotBlank();
    }

    // ------------------------------------------------------------------ who may do it

    @Test
    @DisplayName("issuing and revoking are OWNER/ADMIN; a MEMBER may still see which keys exist")
    void rolesAreEnforced() throws Exception {
        create(memberToken, body("sneaky", "production", null), 403);

        mockMvc.perform(authorized(get("/api/v1/api-keys"), memberToken))
                .andExpect(status().isOk());

        String id = create(ownerToken, body("ci", "production", null), 201).at("/apiKey/id").asText();
        mockMvc.perform(authorized(delete("/api/v1/api-keys/" + id), memberToken))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ revoking

    @Test
    @DisplayName("revoking stops the key, and saying so twice is not an error")
    void revokeIsIdempotentAndImmediate() throws Exception {
        JsonNode created = create(ownerToken, body("doomed", "production", null), 201);
        String key = created.get("key").asText();
        String id = created.at("/apiKey/id").asText();

        mockMvc.perform(withKey(get("/api/v1/sdk/config"), key)).andExpect(status().isOk());

        mockMvc.perform(authorized(delete("/api/v1/api-keys/" + id), ownerToken))
                .andExpect(status().isNoContent());

        // No cache stands between revocation and effect.
        mockMvc.perform(withKey(get("/api/v1/sdk/config"), key))
                .andExpect(status().isUnauthorized());

        // A retried revocation describes a state that already holds; it is not a failure.
        mockMvc.perform(authorized(delete("/api/v1/api-keys/" + id), ownerToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(authorized(get("/api/v1/api-keys"), ownerToken))
                .andExpect(jsonPath("$.apiKeys[0].revokedAt").isNotEmpty());
    }

    @Test
    @DisplayName("a key id belonging to another tenant cannot be revoked, and is reported as not found")
    void revokeCannotReachAcrossTenants() throws Exception {
        String acmeKeyId = create(ownerToken, body("acme-ci", "production", null), 201)
                .at("/apiKey/id").asText();

        String globexOwner = tokenFor(globex, RoleName.OWNER, "gx-owner");
        mockMvc.perform(authorized(delete("/api/v1/api-keys/" + acmeKeyId), globexOwner))
                .andExpect(status().isNotFound());

        assertThat(apiKeyRepository.findById(Long.valueOf(acmeKeyId)).orElseThrow().getRevokedAt()).isNull();
    }

    @Test
    @DisplayName("one tenant's listing never shows another's keys")
    void listingIsTenantScoped() throws Exception {
        create(ownerToken, body("acme-ci", "production", null), 201);

        String globexOwner = tokenFor(globex, RoleName.OWNER, "gx-owner");
        mockMvc.perform(authorized(get("/api/v1/api-keys"), globexOwner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeys").isEmpty());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode create(String token, String json, int expectedStatus) throws Exception {
        String response = mockMvc.perform(authorized(post("/api/v1/api-keys"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private String body(String name, String envName, Instant expiresAt) throws Exception {
        return objectMapper.writeValueAsString(expiresAt == null
                ? java.util.Map.of("name", name, "envName", envName)
                : java.util.Map.of("name", name, "envName", envName, "expiresAt", expiresAt.toString()));
    }

    private String tokenFor(Tenant tenant, RoleName roleName, String handle) {
        Role role = roleRepository.findByName(roleName.name()).orElseThrow();
        User user = userRepository.save(User.builder()
                .email(handle + "-" + tenant.getId() + "@test.com").firstName("Test")
                .passwordHash("hash").status(UserStatus.ACTIVE).build());
        membershipRepository.save(Membership.builder()
                .user(user).tenant(tenant).status(MembershipStatus.ACTIVE)
                .roles(new HashSet<>(Set.of(role))).build());
        return jwtTokenProvider.generateAccessToken(
                user.getId(), user.getEmail(), tenant.getId(), List.of(roleName.name()));
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder, String token) {
        return fromFreshAddress(builder).header("Authorization", "Bearer " + token);
    }

    private MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder builder, String key) {
        return fromFreshAddress(builder).header(ApiKeyAuthenticationFilter.API_KEY_HEADER, key);
    }

    private MockHttpServletRequestBuilder fromFreshAddress(MockHttpServletRequestBuilder builder) {
        return builder.with(request -> {
            request.setRemoteAddr("10.3.0." + (CLIENT_ADDRESS_SEQUENCE.incrementAndGet() % 250 + 1));
            return request;
        });
    }
}
