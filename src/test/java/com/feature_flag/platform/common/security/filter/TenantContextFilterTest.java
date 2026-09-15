package com.feature_flag.platform.common.security.filter;

import com.feature_flag.platform.auth.entity.Role;
import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.common.config.I18N;
import com.feature_flag.platform.common.exception.TenantAccessDeniedException;
import com.feature_flag.platform.common.security.TenantMembershipResolver;
import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.tenant.entity.Membership;
import com.feature_flag.platform.tenant.entity.Tenant;
import com.feature_flag.platform.tenant.enums.MembershipStatus;
import com.feature_flag.platform.util.TestEntityUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The security contract of {@link TenantContextFilter}: the tenant a request acts in is only ever one the
 * caller holds an ACTIVE membership in, whether it arrived in the header or in the token.
 */
@ExtendWith(MockitoExtension.class)
class TenantContextFilterTest {

    private static final String PROTECTED_PATH = "/api/v1/features";

    @Mock private TenantMembershipResolver tenantMembershipResolver;

    private TenantContextFilter filter;
    private MockFilterChain filterChain;
    private MockedStatic<I18N> i18nMock;

    @BeforeEach
    void setUp() {
        i18nMock = mockStatic(I18N.class);
        i18nMock.when(() -> I18N.getMessage(anyString())).thenAnswer(inv -> inv.getArgument(0));
        i18nMock.when(() -> I18N.getMessage(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));

        // Mirrors the auto-configured mapper Spring Boot injects: ErrorResponse carries an Instant, which
        // needs the JSR-310 module to serialize.
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        filter = new TenantContextFilter(tenantMembershipResolver, new FilterErrorWriter(objectMapper));
        filterChain = new MockFilterChain();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        i18nMock.close();
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(Long userId, Long tokenTenantId, String... roles) {
        TenantUserDetails principal =
                new TenantUserDetails(userId, "user@test.com", tokenTenantId, List.of(roles));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private Membership activeMembershipWith(String roleName) {
        User user = TestEntityUtil.withId(User.builder()
                .email("user@test.com").firstName("John").passwordHash("hash").build(), 1L);
        Tenant tenant = TestEntityUtil.withId(Tenant.builder().name("Other").build(), 2L);
        Role role = TestEntityUtil.withId(Role.builder().name(roleName).build(), 10L);
        return TestEntityUtil.withId(Membership.builder()
                .user(user).tenant(tenant).status(MembershipStatus.ACTIVE).roles(Set.of(role)).build(), 200L);
    }

    private MockHttpServletRequest request(String path, String tenantHeader) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath(path);
        if (tenantHeader != null) {
            request.addHeader(TenantContextFilter.TENANT_HEADER, tenantHeader);
        }
        return request;
    }

    private TenantUserDetails currentPrincipal() {
        return (TenantUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    @Test
    void header_overridesTokenTenant_andRolesComeFromThatMembership() throws ServletException, IOException {
        authenticateAs(1L, 1L, "OWNER");
        Membership membership = activeMembershipWith("ADMIN");
        when(tenantMembershipResolver.requireActiveMembership(1L, 2L)).thenReturn(membership);
        when(tenantMembershipResolver.roleNames(membership)).thenReturn(List.of("ADMIN"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(PROTECTED_PATH, "2"), response, filterChain);

        assertEquals(2L, currentPrincipal().getTenantId());
        assertEquals(List.of("ADMIN"), currentPrincipal().getRoles(),
                "roles must be re-resolved for the target tenant, not carried over from the token");
        assertNotNull(filterChain.getRequest(), "request should have continued down the chain");
    }

    @Test
    void noHeader_fallsBackToTokenTenant() throws ServletException, IOException {
        authenticateAs(1L, 7L, "OWNER");
        Membership membership = activeMembershipWith("OWNER");
        when(tenantMembershipResolver.requireActiveMembership(1L, 7L)).thenReturn(membership);
        when(tenantMembershipResolver.roleNames(membership)).thenReturn(List.of("OWNER"));

        filter.doFilter(request(PROTECTED_PATH, null), new MockHttpServletResponse(), filterChain);

        assertEquals(7L, currentPrincipal().getTenantId());
        verify(tenantMembershipResolver).requireActiveMembership(1L, 7L);
    }

    @Test
    void blankHeader_fallsBackToTokenTenant() throws ServletException, IOException {
        authenticateAs(1L, 7L, "OWNER");
        Membership membership = activeMembershipWith("OWNER");
        when(tenantMembershipResolver.requireActiveMembership(1L, 7L)).thenReturn(membership);
        when(tenantMembershipResolver.roleNames(membership)).thenReturn(List.of("OWNER"));

        filter.doFilter(request(PROTECTED_PATH, "   "), new MockHttpServletResponse(), filterChain);

        assertEquals(7L, currentPrincipal().getTenantId());
    }

    @Test
    void noMembershipInRequestedTenant_isForbidden() throws ServletException, IOException {
        authenticateAs(1L, 1L, "OWNER");
        when(tenantMembershipResolver.requireActiveMembership(1L, 99L))
                .thenThrow(new TenantAccessDeniedException("no membership"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(PROTECTED_PATH, "99"), response, filterChain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("no membership"));
        assertNull(filterChain.getRequest(), "the request must not reach the controller");
    }

    @Test
    void inactiveMembership_isForbidden() throws ServletException, IOException {
        authenticateAs(1L, 1L, "OWNER");
        when(tenantMembershipResolver.requireActiveMembership(1L, 2L))
                .thenThrow(new TenantAccessDeniedException("not active"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(PROTECTED_PATH, "2"), response, filterChain);

        assertEquals(403, response.getStatus());
        assertNull(filterChain.getRequest());
    }

    @Test
    void malformedHeader_isBadRequest() throws ServletException, IOException {
        authenticateAs(1L, 1L, "OWNER");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(PROTECTED_PATH, "not-a-number"), response, filterChain);

        assertEquals(400, response.getStatus());
        assertNull(filterChain.getRequest());
        verifyNoInteractions(tenantMembershipResolver);
    }

    @Test
    void noTenantInHeaderOrToken_isForbidden() throws ServletException, IOException {
        authenticateAs(1L, null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(PROTECTED_PATH, null), response, filterChain);

        assertEquals(403, response.getStatus());
        assertNull(filterChain.getRequest());
        verifyNoInteractions(tenantMembershipResolver);
    }

    @Test
    void unauthenticated_passesThroughUntouched() throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(PROTECTED_PATH, "2"), response, filterChain);

        assertEquals(200, response.getStatus());
        assertNotNull(filterChain.getRequest());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(tenantMembershipResolver);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/auth/switch-tenant",
            "/api/v1/tenants"
    })
    void tenantLessPaths_areNotFiltered(String path) {
        assertTrue(filter.shouldNotFilter(request(path, null)),
                "tenant scope for this endpoint comes from the body, not the header");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/features",
            "/api/v1/envs",
            "/api/v1/config",
            "/api/v1/members",
            "/api/v1/invitations",
            // Takes its tenant from the path, but must still be filtered: skipping it left the OWNER role
            // check on PUT/DELETE running against the caller's own default tenant.
            "/api/v1/tenants/12345"
    })
    void tenantScopedPaths_areFiltered(String path) {
        assertFalse(filter.shouldNotFilter(request(path, null)));
    }

    @Test
    void tenantPath_resolvesTheTenantFromThePath_notTheToken() throws ServletException, IOException {
        authenticateAs(1L, 1L, "OWNER");
        Membership membership = activeMembershipWith("VIEWER");
        when(tenantMembershipResolver.requireActiveMembership(1L, 2L)).thenReturn(membership);
        when(tenantMembershipResolver.roleNames(membership)).thenReturn(List.of("VIEWER"));

        filter.doFilter(request("/api/v1/tenants/2", null), new MockHttpServletResponse(), filterChain);

        assertEquals(2L, currentPrincipal().getTenantId());
        assertEquals(List.of("VIEWER"), currentPrincipal().getRoles(),
                "the OWNER in the token must not survive into a tenant the caller only views");
        verify(tenantMembershipResolver).requireActiveMembership(1L, 2L);
    }

    @Test
    void tenantPath_ignoresTheHeader() throws ServletException, IOException {
        authenticateAs(1L, 1L, "OWNER");
        Membership membership = activeMembershipWith("OWNER");
        when(tenantMembershipResolver.requireActiveMembership(1L, 2L)).thenReturn(membership);
        when(tenantMembershipResolver.roleNames(membership)).thenReturn(List.of("OWNER"));

        filter.doFilter(request("/api/v1/tenants/2", "99"), new MockHttpServletResponse(), filterChain);

        assertEquals(2L, currentPrincipal().getTenantId());
        verify(tenantMembershipResolver, never()).requireActiveMembership(1L, 99L);
    }

    @Test
    void tenantPath_withoutMembership_isForbidden() throws ServletException, IOException {
        authenticateAs(1L, 1L, "OWNER");
        when(tenantMembershipResolver.requireActiveMembership(1L, 99L))
                .thenThrow(new TenantAccessDeniedException("no membership"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("/api/v1/tenants/99", null), response, filterChain);

        assertEquals(403, response.getStatus());
        assertNull(filterChain.getRequest(), "the request must not reach the controller");
    }

    @Test
    void tenantPath_thatIsNotNumeric_isBadRequest() throws ServletException, IOException {
        authenticateAs(1L, 1L, "OWNER");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("/api/v1/tenants/not-a-number", null), response, filterChain);

        assertEquals(400, response.getStatus());
        assertNull(filterChain.getRequest());
        verifyNoInteractions(tenantMembershipResolver);
    }

    @Test
    void requestDetails_arePreservedOnTheRescopedAuthentication() throws ServletException, IOException {
        TenantUserDetails principal = new TenantUserDetails(1L, "user@test.com", 1L, List.of("OWNER"));
        UsernamePasswordAuthenticationToken original =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        original.setDetails("original-details");
        SecurityContextHolder.getContext().setAuthentication(original);

        Membership membership = activeMembershipWith("ADMIN");
        when(tenantMembershipResolver.requireActiveMembership(1L, 2L)).thenReturn(membership);
        when(tenantMembershipResolver.roleNames(membership)).thenReturn(List.of("ADMIN"));

        filter.doFilter(request(PROTECTED_PATH, "2"), new MockHttpServletResponse(), filterChain);

        assertEquals("original-details", SecurityContextHolder.getContext().getAuthentication().getDetails());
    }
}
