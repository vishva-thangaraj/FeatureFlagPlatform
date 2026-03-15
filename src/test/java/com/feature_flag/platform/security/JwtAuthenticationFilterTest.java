package com.feature_flag.platform.security;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/auth/logout"
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
}
