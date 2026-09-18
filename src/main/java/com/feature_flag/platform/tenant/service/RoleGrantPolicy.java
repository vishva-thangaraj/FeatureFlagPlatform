package com.feature_flag.platform.tenant.service;

import com.feature_flag.platform.auth.enums.RoleName;
import com.feature_flag.platform.common.config.I18N;
import com.feature_flag.platform.common.config.Messages;
import com.feature_flag.platform.common.exception.BadRequestException;
import com.feature_flag.platform.common.exception.ForbiddenException;
import com.feature_flag.platform.common.exception.UnauthorizedException;
import com.feature_flag.platform.common.security.TenantUserDetails;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The single place that decides whether a caller may hand out a given role.
 *
 * <p><b>The rule:</b> a caller may only grant a role at or below their own rank in the tenant they are
 * acting in. There are exactly two ways to hand someone a role — inviting them
 * ({@code InvitationServiceImpl.inviteUserToTenant}) and changing an existing membership
 * ({@code MembershipService.updateMemberRole}) — and both go through here, because guarding only one of
 * them leaves the other as a complete bypass.
 *
 * <p>Both endpoints are open to OWNER <em>and</em> ADMIN in {@code api-security.json}, and neither used to
 * constrain the role being granted. An ADMIN could therefore invite a second address they control as OWNER
 * (or as the dormant SUPER_ADMIN) and accept it, or simply PUT {@code {"roleName":"OWNER"}} onto their own
 * membership. {@code updateMemberRole}'s existing guard only refuses to change an <em>existing</em> OWNER's
 * role, which says nothing about the role being granted, so the intended ceiling was never enforced at all.
 *
 * <p>Ranking comes from {@link RoleName#rank()}, never from {@code ordinal()} — see the note on the enum.
 */
@Component
public class RoleGrantPolicy {

    /**
     * Validates a role name supplied by a client.
     *
     * <p>400, not 500: the old code looked the name up in the {@code roles} table first and only then called
     * {@code RoleName.valueOf}. Under MySQL's default case-insensitive collation the lookup accepts
     * {@code "admin"}, so {@code valueOf} then threw {@link IllegalArgumentException} out of the service.
     */
    public RoleName requireKnownRole(String role) {
        return RoleName.parse(role).orElseThrow(() ->
                new BadRequestException(I18N.getMessage(Messages.ROLE_NAME_INVALID, String.valueOf(role))));
    }

    /**
     * Enforces the ceiling for a caller whose principal is already in hand (the invitation path receives it
     * as a controller argument).
     */
    public void requireMayGrant(TenantUserDetails caller, RoleName requested) {
        if (caller == null) {
            throw new UnauthorizedException(I18N.getMessage(Messages.UNAUTHORIZED));
        }
        RoleName callerRank = RoleName.highestOf(caller.getRoles()).orElseThrow(() ->
                new ForbiddenException(I18N.getMessage(Messages.ROLE_GRANT_ABOVE_OWN_RANK, requested.name())));

        if (!callerRank.outranksOrEquals(requested)) {
            throw new ForbiddenException(
                    I18N.getMessage(Messages.ROLE_GRANT_ABOVE_OWN_RANK, requested.name()));
        }
    }

    /**
     * Enforces the ceiling for the caller of the current request.
     *
     * <p>{@code MembersController} passes only the acting tenant id down, so the principal is read back from
     * the security context here rather than changing that controller's signature. It is the same principal
     * the controller holds: {@code TenantContextFilter} has already replaced the authentication with one
     * scoped to the tenant being acted in, so {@code getRoles()} is the caller's role set <em>in this
     * tenant</em> — not whatever was baked into their JWT for some other tenant.
     */
    public void requireCurrentCallerMayGrant(RoleName requested) {
        requireMayGrant(currentCaller().orElse(null), requested);
    }

    private Optional<TenantUserDetails> currentCaller() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof TenantUserDetails principal)) {
            return Optional.empty();
        }
        return Optional.of(principal);
    }
}
