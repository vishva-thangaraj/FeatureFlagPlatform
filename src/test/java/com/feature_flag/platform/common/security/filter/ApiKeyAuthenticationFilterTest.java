package com.feature_flag.platform.common.security.filter;

import com.feature_flag.platform.common.config.I18N;
import com.feature_flag.platform.common.security.ApiKeyGenerator;
import com.feature_flag.platform.common.security.ApiKeyPrincipal;
import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.common.security.TokenHashUtil;
import com.feature_flag.platform.tenant.dto.ApiKeyIdentity;
import com.feature_flag.platform.tenant.repository.ApiKeyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The security contract of {@link ApiKeyAuthenticationFilter}.
 *
 * <p>Liveness is not asserted here by stubbing a revoked key: the query is what enforces it, so these
 * tests pin that the filter asks for an <em>active</em> key and authenticates nobody when the repository
 * finds none. {@code ApiKeyRepositoryTest} covers which keys that query returns.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiKeyAuthenticationFilterTest {

    private static final String SDK_PATH = "/api/v1/sdk/config";

    @Mock private ApiKeyRepository apiKeyRepository;

    private ApiKeyAuthenticationFilter filter;
    private MockFilterChain filterChain;
    private MockedStatic<I18N> i18nMock;
    private String validKey;

    @BeforeEach
    void setUp() {
        i18nMock = mockStatic(I18N.class);
        i18nMock.when(() -> I18N.getMessage(anyString())).thenAnswer(inv -> inv.getArgument(0));
        i18nMock.when(() -> I18N.getMessage(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));

        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        filter = new ApiKeyAuthenticationFilter(apiKeyRepository, new FilterErrorWriter(objectMapper));
        filterChain = new MockFilterChain();
        validKey = ApiKeyGenerator.generate().plaintext();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        i18nMock.close();
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ accepting a key

    @Test
    @DisplayName("a live key authenticates, carrying both the tenant and the environment it is bound to")
    void authenticatesWithTenantAndEnv() throws Exception {
        stubActive(validKey, new ApiKeyIdentity(7L, 42L, 99L, "production", "ffsdk_abc123"));

        MockHttpServletResponse response = run(request(SDK_PATH, validKey));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(filterChain.getRequest()).isNotNull();

        ApiKeyPrincipal principal = (ApiKeyPrincipal) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        assertThat(principal.getApiKeyId()).isEqualTo(7L);
        assertThat(principal.getTenantId()).isEqualTo(42L);
        assertThat(principal.getEnvId()).isEqualTo(99L);
        assertThat(principal.getEnvName()).isEqualTo("production");
    }

    @Test
    @DisplayName("the key's authority matches no role in the policy, so it can never satisfy a role gate")
    void carriesNoBusinessRole() throws Exception {
        stubActive(validKey, identity());

        run(request(SDK_PATH, validKey));

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_SDK")
                .doesNotContain("ROLE_OWNER", "ROLE_ADMIN", "ROLE_MEMBER", "ROLE_VIEWER");
    }

    @Test
    @DisplayName("liveness is part of the lookup, not a check the filter could forget")
    void asksOnlyForActiveKeys() throws Exception {
        stubActive(validKey, identity());

        run(request(SDK_PATH, validKey));

        verify(apiKeyRepository).findActiveByKeyHash(eq(TokenHashUtil.sha256(validKey)), any(Instant.class));
        verify(apiKeyRepository, never()).findById(anyLong());
    }

    // ------------------------------------------------------------------ refusing a key

    @Test
    @DisplayName("a key the lookup does not return — unknown, revoked or expired — is 401, not 403")
    void unknownKeyIsUnauthorized() throws Exception {
        when(apiKeyRepository.findActiveByKeyHash(anyString(), any())).thenReturn(Optional.empty());

        MockHttpServletResponse response = run(request(SDK_PATH, validKey));

        // 401 and not the API-wide 403: an SDK must tell a dead key, which it should stop retrying,
        // from a throttle, which it should retry with backoff.
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(filterChain.getRequest()).as("the request must not reach the endpoint").isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("a value that cannot be one of our keys is refused without touching the database")
    void malformedKeyCostsNoQuery() throws Exception {
        MockHttpServletResponse response = run(request(SDK_PATH, "not-a-key"));

        assertThat(response.getStatus()).isEqualTo(401);
        verify(apiKeyRepository, never()).findActiveByKeyHash(anyString(), any());
    }

    @Test
    @DisplayName("a refusal is the API's JSON error shape, never a container error page")
    void refusalKeepsTheErrorShape() throws Exception {
        when(apiKeyRepository.findActiveByKeyHash(anyString(), any())).thenReturn(Optional.empty());

        MockHttpServletResponse response = run(request(SDK_PATH, validKey));

        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).contains("\"status\":401");
    }

    @Test
    @DisplayName("a key and a bearer token together are refused rather than silently ranked")
    void ambiguousCredentialsAreRefused() throws Exception {
        TenantUserDetails user = new TenantUserDetails(1L, "user@test.com", 42L, List.of("OWNER"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        stubActive(validKey, identity());

        MockHttpServletResponse response = run(request(SDK_PATH, validKey));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(filterChain.getRequest()).isNull();
    }

    // ------------------------------------------------------------------ where it applies

    @Test
    @DisplayName("no key header leaves the context untouched for the rest of the chain to judge")
    void noHeaderIsAPassThrough() throws Exception {
        MockHttpServletResponse response = run(request(SDK_PATH, null));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(filterChain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("a key presented to the dashboard API authenticates nothing")
    void keysDoNotWorkOutsideTheSdkSurface() throws Exception {
        stubActive(validKey, identity());

        // Every other endpoint reads TenantUserDetails out of the context. A key accepted here would
        // authenticate a request whose controller then fails on a principal of the wrong type, so the
        // header is ignored outright and the request is simply unauthenticated.
        for (String path : List.of("/api/v1/features", "/api/v1/tenants/42", "/api/v1/config")) {
            SecurityContextHolder.clearContext();
            MockHttpServletRequest request = request(path, validKey);

            assertThat(filter.shouldNotFilter(request)).as(path).isTrue();

            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
            assertThat(SecurityContextHolder.getContext().getAuthentication()).as(path).isNull();
        }
    }

    // ------------------------------------------------------------------ usage stamping

    @Test
    @DisplayName("repeated polling stamps last-used once, not once per request")
    void lastUsedWritesAreCoalesced() throws Exception {
        stubActive(validKey, identity());

        for (int i = 0; i < 25; i++) {
            SecurityContextHolder.clearContext();
            filter.doFilter(request(SDK_PATH, validKey), new MockHttpServletResponse(), new MockFilterChain());
        }

        verify(apiKeyRepository).touchLastUsedAt(eq(1L), any(Instant.class));
    }

    @Test
    @DisplayName("a failed usage stamp does not fail the request")
    void bookkeepingFailureIsNotTheCallersProblem() throws Exception {
        stubActive(validKey, identity());
        when(apiKeyRepository.touchLastUsedAt(anyLong(), any()))
                .thenThrow(new IllegalStateException("database is having a day"));

        MockHttpServletResponse response = run(request(SDK_PATH, validKey));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(filterChain.getRequest()).isNotNull();
    }

    // ------------------------------------------------------------------ helpers

    private ApiKeyIdentity identity() {
        return new ApiKeyIdentity(1L, 42L, 99L, "production", "ffsdk_abc123");
    }

    private void stubActive(String key, ApiKeyIdentity identity) {
        when(apiKeyRepository.findActiveByKeyHash(eq(TokenHashUtil.sha256(key)), any()))
                .thenReturn(Optional.of(identity));
    }

    private MockHttpServletRequest request(String path, String apiKey) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath(path);
        if (apiKey != null) {
            request.addHeader(ApiKeyAuthenticationFilter.API_KEY_HEADER, apiKey);
        }
        return request;
    }

    private MockHttpServletResponse run(MockHttpServletRequest request) throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, filterChain);
        return response;
    }
}
