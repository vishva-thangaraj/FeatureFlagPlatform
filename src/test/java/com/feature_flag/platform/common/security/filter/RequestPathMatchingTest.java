package com.feature_flag.platform.common.security.filter;

import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.common.security.config.ApiSecurityConfigLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The path every rule-matching filter matches on.
 *
 * <p>{@code request.getServletPath()} returns the full path only because the dispatcher servlet happens to
 * be mapped at {@code /}; it is EMPTY whenever the request did not go through a servlet mapping, which is
 * every request under MockMvc. {@code RoleAuthorizationFilter}, {@code RateLimitFilter} and
 * {@code ParamValidationFilter} all matched on it, so under test they looked up the rule for {@code ""},
 * found none, and let everything through. Production was correct; the test suite was proving nothing.
 */
class RequestPathMatchingTest {

    private ApiSecurityConfigLoader configLoader;
    private FilterErrorWriter errorWriter;

    @BeforeEach
    void setUp() {
        configLoader = new ApiSecurityConfigLoader(new ObjectMapper(), "api-security.json");
        configLoader.load();
        errorWriter = new FilterErrorWriter(new ObjectMapper().registerModule(new JavaTimeModule()));
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** A request shaped the way MockMvc shapes one: the path is in the URI, the servlet path is empty. */
    private MockHttpServletRequest mockMvcStyleRequest(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath("");
        request.setRemoteAddr("198.51.100.1");
        return request;
    }

    private void authenticateAs(String... roles) {
        TenantUserDetails principal = new TenantUserDetails(1L, "u@test.com", 2L, List.of(roles));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    // ---------------------------------------------------------------- RequestPaths itself

    @Test
    void requestPaths_prefersTheRequestUri() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/features");
        request.setServletPath("");

        assertThat(RequestPaths.of(request)).isEqualTo("/api/v1/features");
    }

    @Test
    void requestPaths_stripsTheContextPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/platform/api/v1/features");
        request.setContextPath("/platform");

        assertThat(RequestPaths.of(request)).isEqualTo("/api/v1/features");
    }

    @Test
    void requestPaths_fallsBackToTheServletPathWhenThereIsNoUri() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("");
        request.setServletPath("/api/v1/features");

        assertThat(RequestPaths.of(request)).isEqualTo("/api/v1/features");
    }

    // ---------------------------------------------------------------- RoleAuthorizationFilter

    @Test
    void roleFilter_appliesTheRuleWhenOnlyTheRequestUriCarriesThePath() throws Exception {
        RoleAuthorizationFilter filter = new RoleAuthorizationFilter(configLoader, errorWriter);
        authenticateAs("VIEWER");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // POST /api/v1/features declares "roles": ["OWNER", "ADMIN"].
        filter.doFilter(mockMvcStyleRequest("POST", "/api/v1/features"), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).as("the request must not have reached the controller").isNull();
    }

    @Test
    void roleFilter_appliesPatternRulesToo() throws Exception {
        RoleAuthorizationFilter filter = new RoleAuthorizationFilter(configLoader, errorWriter);
        authenticateAs("VIEWER");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(mockMvcStyleRequest("DELETE", "/api/v1/tenants/42"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void roleFilter_letsTheRoleHolderThrough() throws Exception {
        RoleAuthorizationFilter filter = new RoleAuthorizationFilter(configLoader, errorWriter);
        authenticateAs("ADMIN");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(mockMvcStyleRequest("POST", "/api/v1/features"), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    // ---------------------------------------------------------------- ParamValidationFilter

    @Test
    void paramFilter_appliesTheAllowListWhenOnlyTheRequestUriCarriesThePath() throws Exception {
        ParamValidationFilter filter = new ParamValidationFilter(configLoader, errorWriter);
        MockHttpServletRequest request = mockMvcStyleRequest("GET", "/api/v1/config");
        // GET /api/v1/config declares "allowedParams": ["envName"].
        request.setParameter("envName", "prod");
        request.setParameter("unexpected", "value");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("unexpected");
    }

    @Test
    void paramFilter_allowsTheDeclaredParameters() throws Exception {
        ParamValidationFilter filter = new ParamValidationFilter(configLoader, errorWriter);
        MockHttpServletRequest request = mockMvcStyleRequest("GET", "/api/v1/config");
        request.setParameter("envName", "prod");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }
}
