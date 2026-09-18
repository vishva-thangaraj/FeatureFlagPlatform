package com.feature_flag.platform.common.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private FilterChain filterChain;
    @Mock private Claims claims;

    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtTokenProvider);
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout"
    })
    void shouldNotFilter_publicPaths(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath(path);
        assertTrue(filter.shouldNotFilter(request));
    }

    @Test
    void shouldFilter_protectedPaths() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/api/flags");
        assertFalse(filter.shouldNotFilter(request));
    }

    @Test
    void doFilter_validToken_setsSecurityContext() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/api/flags");
        request.addHeader("Authorization", "Bearer valid-jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(jwtTokenProvider.validateAccessToken("valid-jwt")).thenReturn(true);
        when(jwtTokenProvider.parseAccessToken("valid-jwt")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("42");
        when(claims.get("email", String.class)).thenReturn("user@test.com");
        when(claims.get("tenantId", Long.class)).thenReturn(100L);
        when(claims.get("roles", List.class)).thenReturn(List.of("OWNER"));

        filter.doFilterInternal(request, response, filterChain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        TenantUserDetails details = (TenantUserDetails) auth.getPrincipal();
        assertEquals(42L, details.getUserId());
        assertEquals("user@test.com", details.getEmail());
        assertEquals(100L, details.getTenantId());
        assertEquals(List.of("OWNER"), details.getRoles());
    }

    @Test
    void doFilter_validToken_nullTenantId_setsNullTenant() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/api/flags");
        request.addHeader("Authorization", "Bearer valid-jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(jwtTokenProvider.validateAccessToken("valid-jwt")).thenReturn(true);
        when(jwtTokenProvider.parseAccessToken("valid-jwt")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("1");
        when(claims.get("email", String.class)).thenReturn("u@t.com");
        when(claims.get("tenantId", Long.class)).thenReturn(null);
        when(claims.get("roles", List.class)).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        TenantUserDetails details = (TenantUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertNull(details.getTenantId());
        assertTrue(details.getRoles().isEmpty());
    }

    @Test
    void doFilter_invalidToken_doesNotSetContext() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/api/flags");
        request.addHeader("Authorization", "Bearer bad-jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(jwtTokenProvider.validateAccessToken("bad-jwt")).thenReturn(false);

        filter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void doFilter_noAuthHeader_doesNotSetContext() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/api/flags");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void doFilter_nonBearerHeader_doesNotSetContext() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/api/flags");
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    // ------------------------------------------------------------------ the path the skip list matches on

    /**
     * {@code getServletPath()} is empty for any request that did not go through a servlet mapping — every
     * request under MockMvc — so this skip list silently matched nothing there and the filter ran on the
     * credential endpoints under test while skipping them in production.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout"
    })
    void shouldNotFilter_publicPaths_whenOnlyTheRequestUriCarriesThePath(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath("");

        assertTrue(filter.shouldNotFilter(request),
                "the skip list must be matched against the request URI, not the servlet path");
    }

    @Test
    void shouldNotFilter_stripsTheContextPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/platform/api/v1/auth/login");
        request.setContextPath("/platform");
        request.setServletPath("");

        assertTrue(filter.shouldNotFilter(request));
    }

    // ------------------------------------------------------------------ malformed claims

    /**
     * A well-signed token whose {@code sub} is not a number used to throw NumberFormatException straight
     * out of the filter chain, past ExceptionTranslationFilter and past @RestControllerAdvice, so the
     * caller got the container's HTML error page. It is an unusable credential, not a server fault: no
     * principal, and the authorization rules answer in the normal shape.
     */
    @Test
    void doFilter_tokenWithNonNumericSubject_doesNotSetContextAndDoesNotThrow()
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/features");
        request.addHeader("Authorization", "Bearer weird-jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(jwtTokenProvider.validateAccessToken("weird-jwt")).thenReturn(true);
        when(jwtTokenProvider.parseAccessToken("weird-jwt")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("not-a-user-id");

        assertDoesNotThrow(() -> filter.doFilterInternal(request, response, filterChain));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void doFilter_tokenWithNoSubject_doesNotSetContext() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/features");
        request.addHeader("Authorization", "Bearer weird-jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(jwtTokenProvider.validateAccessToken("weird-jwt")).thenReturn(true);
        when(jwtTokenProvider.parseAccessToken("weird-jwt")).thenReturn(claims);
        when(claims.getSubject()).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    /** A claim of the wrong type (tenantId as a string, say) is the same kind of unusable credential. */
    @Test
    void doFilter_tokenWithAWronglyTypedClaim_doesNotSetContext() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/features");
        request.addHeader("Authorization", "Bearer weird-jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(jwtTokenProvider.validateAccessToken("weird-jwt")).thenReturn(true);
        when(jwtTokenProvider.parseAccessToken("weird-jwt")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("42");
        when(claims.get("email", String.class)).thenReturn("u@t.com");
        when(claims.get("tenantId", Long.class))
                .thenThrow(new io.jsonwebtoken.RequiredTypeException("tenantId is a String"));

        assertDoesNotThrow(() -> filter.doFilterInternal(request, response, filterChain));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
