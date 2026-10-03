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
 * <p>{@code /api/v1/tenants/{tenantId}} names its tenant in the path instead, so for those requests the
 * path segment is the tenant and the header is ignored. This filter used to skip them entirely, which left
 * {@code RoleAuthorizationFilter} checking the {@code OWNER} requirement on PUT and DELETE against the
 * roles baked into the caller's JWT — the roles in their <em>own default</em> tenant. Anyone who created a
 * tenant therefore carried {@code OWNER} everywhere and could rename or delete any tenant on the platform.
 * Resolving the path tenant here makes those declarative role rules mean what they say.
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
            // Ending your own session has nothing to do with which tenant you are acting in, and the
            // callers who most need it have no tenant context to offer: a user who belongs to no tenant
            // yet, or whose only membership was just revoked. Scoping it would answer them 403 and leave
            // them unable to log out at all. (Logout became authenticated so that it could verify you own
            // the refresh token you present; that must not make it tenant-scoped as a side effect.)
            "/api/v1/auth/logout",
            "/api/v1/tenants");

    /** Everything under it names its tenant in the first path segment rather than in the header. */
    private static final String TENANT_PATH_PREFIX = "/api/v1/tenants/";

    /**
     * Token-addressed invitation endpoints ({@code /{inviteToken}}, {@code .../accept},
     * {@code .../decline}). The invitation itself names the tenant, so the caller has no tenant context
     * yet — an invitee who belongs to no tenant at all, or who is being invited to their second one,
     * would otherwise be refused here before the controller ever runs.
     *
     * <p>Deliberately a prefix and not the collection path: {@code POST /api/v1/invitations} <em>creates</em>
     * an invitation in the caller's acting tenant and must stay scoped.
     */
    private static final String INVITATION_TOKEN_PATH_PREFIX = "/api/v1/invitations/";

    /**
     * The SDK surface, authenticated by API key. The key names its own tenant, and there is no user and
     * therefore no membership to resolve. Stated here rather than left to the fact that an
     * {@code ApiKeyPrincipal} is not a {@code TenantUserDetails} and so falls through below: a bypass of
     * the filter that closed the cross-tenant IDOR should be written down, not inferred.
     */
    private static final String SDK_PATH_PREFIX = ApiKeyAuthenticationFilter.SDK_PATH_PREFIX;

    private final TenantMembershipResolver tenantMembershipResolver;
    private final FilterErrorWriter errorWriter;

    public TenantContextFilter(TenantMembershipResolver tenantMembershipResolver,
                               FilterErrorWriter errorWriter) {
        this.tenantMembershipResolver = tenantMembershipResolver;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = requestPath(request);
        return TENANT_LESS_PATHS.contains(path)
                || path.startsWith(INVITATION_TOKEN_PATH_PREFIX)
                || path.startsWith(SDK_PATH_PREFIX);
    }

    /**
     * The request path this filter matches its rules against, context path stripped.
     *
     * <p>See {@link RequestPaths} for why this is not {@code getServletPath()}. The other rule-matching
     * filters now share that helper, so all of them agree on what "the path" is.
     */
    private String requestPath(HttpServletRequest request) {
        return RequestPaths.of(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // Unauthenticated (a public endpoint, or a request with no/invalid bearer token) — nothing to
        // scope. This filter only ever runs inside the security chain now: SecurityConfig disables the
        // container-level auto-registration of every security filter, so there is no stray instance that
        // could reach this line ahead of JwtAuthenticationFilter.
        if (authentication == null || !(authentication.getPrincipal() instanceof TenantUserDetails principal)) {
            filterChain.doFilter(request, response);
            return;
        }

        String pathTenantId = pathTenantId(requestPath(request));

        Long requestedTenantId;
        try {
            // Not a ternary: mixing Long.parseLong with a nullable Long unboxes the null branch.
            if (pathTenantId != null) {
                requestedTenantId = Long.valueOf(pathTenantId);
            } else {
                requestedTenantId = resolveRequestedTenantId(request, principal);
            }
        } catch (NumberFormatException e) {
            errorWriter.write(response, HttpStatus.BAD_REQUEST, pathTenantId != null
                    ? I18N.getMessage(Messages.TENANT_PATH_INVALID)
                    : I18N.getMessage(Messages.TENANT_HEADER_INVALID, TENANT_HEADER));
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

    /**
     * The tenant named in {@code /api/v1/tenants/{tenantId}} (and anything nested under it), or null when
     * the path carries no tenant of its own and the header/token decides instead.
     */
    private String pathTenantId(String path) {
        if (!path.startsWith(TENANT_PATH_PREFIX)) {
            return null;
        }
        String remainder = path.substring(TENANT_PATH_PREFIX.length());
        int nextSlash = remainder.indexOf('/');
        String segment = nextSlash < 0 ? remainder : remainder.substring(0, nextSlash);
        return segment.isBlank() ? null : segment;
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
