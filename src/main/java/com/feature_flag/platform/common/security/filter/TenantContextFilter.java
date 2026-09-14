package com.feature_flag.platform.common.security.filter;

import com.feature_flag.platform.common.config.I18N;
import com.feature_flag.platform.common.config.Messages;
import com.feature_flag.platform.common.exception.TenantAccessDeniedException;
import com.feature_flag.platform.common.security.TenantMembershipResolver;
import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.tenant.entity.Membership;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Establishes which tenant the request acts in, and authorizes the caller for it.
 *
 * <p>The tenant used to come only from the JWT, so a user who belonged to several tenants could not reach
 * any but their active one. The workaround was a {@code ?tenantID=} query parameter passed straight to the
 * service layer, which checked only that the resource belonged to the id supplied — never that the caller
 * was a member of that tenant. That was a cross-tenant IDOR.
 *
 * <p>The tenant now arrives in the {@code X-Tenant-Id} header (falling back to the token's tenant claim as
 * the caller's default) and is resolved against an ACTIVE membership on every request. Because the
 * resolved tenant and its roles are written back into the principal, every controller and service keeps
 * reading {@code userDetails.getTenantId()} and is correctly scoped without changes.
 *
 * <p>Runs after {@code JwtAuthenticationFilter} (which supplies the principal) and before
 * {@code RoleAuthorizationFilter}, so role checks apply to the <em>target</em> tenant's roles.
 */
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    /** Header a client sends to act in a tenant other than the one its token defaults to. */
    public static final String TENANT_HEADER = "X-Tenant-Id";

    /**
     * Endpoints that legitimately carry no tenant scope: swapping your default tenant, and the tenant
     * collection itself (listing the tenants you belong to, or creating your first one).
     */
    private static final Set<String> TENANT_LESS_PATHS = Set.of(
            "/api/v1/auth/switch-tenant",
            "/api/v1/tenants");

    /**
     * {@code /api/v1/tenants/{tenantId}} takes its tenant from the path, not from this header, so it is
     * authorized in TenantServiceImpl against the path tenant instead.
     */
    private static final String TENANT_PATH_PREFIX = "/api/v1/tenants/";

    private final TenantMembershipResolver tenantMembershipResolver;
    private final FilterErrorWriter errorWriter;

    public TenantContextFilter(TenantMembershipResolver tenantMembershipResolver,
                               FilterErrorWriter errorWriter) {
        this.tenantMembershipResolver = tenantMembershipResolver;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return TENANT_LESS_PATHS.contains(path) || path.startsWith(TENANT_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // Unauthenticated (public endpoints, or this filter's stray auto-registered instance running
        // ahead of the security chain) — nothing to scope.
        if (authentication == null || !(authentication.getPrincipal() instanceof TenantUserDetails principal)) {
            filterChain.doFilter(request, response);
            return;
        }

        Long requestedTenantId;
        try {
            requestedTenantId = resolveRequestedTenantId(request, principal);
        } catch (NumberFormatException e) {
            errorWriter.write(response, HttpStatus.BAD_REQUEST,
                    I18N.getMessage(Messages.TENANT_HEADER_INVALID, TENANT_HEADER));
            return;
        }

        if (requestedTenantId == null) {
            errorWriter.write(response, HttpStatus.FORBIDDEN,
                    I18N.getMessage(Messages.TENANT_NO_ACTIVE_MEMBERSHIP));
            return;
        }

        TenantUserDetails scoped;
        try {
            Membership membership =
                    tenantMembershipResolver.requireActiveMembership(principal.getUserId(), requestedTenantId);
            scoped = new TenantUserDetails(principal.getUserId(), principal.getEmail(), requestedTenantId,
                    tenantMembershipResolver.roleNames(membership));
        } catch (TenantAccessDeniedException e) {
            errorWriter.write(response, HttpStatus.FORBIDDEN, e.getMessage());
            return;
        }

        UsernamePasswordAuthenticationToken scopedAuthentication =
                new UsernamePasswordAuthenticationToken(scoped, null, scoped.getAuthorities());
        scopedAuthentication.setDetails(authentication.getDetails());
        SecurityContextHolder.getContext().setAuthentication(scopedAuthentication);

        filterChain.doFilter(request, response);
    }

    /** Header wins; the token's claim is the caller's default. Null means neither supplied one. */
    private Long resolveRequestedTenantId(HttpServletRequest request, TenantUserDetails principal) {
        String header = request.getHeader(TENANT_HEADER);
        if (header != null && !header.isBlank()) {
            return Long.parseLong(header.trim());
        }
        return principal.getTenantId();
    }
}
