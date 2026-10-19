package com.feature_flag.platform.auth.dto;

import com.feature_flag.platform.auth.enums.UserStatus;

import java.util.List;

/**
 * Who the caller is. The access token carries no name, so without this the dashboard's account menu has
 * nothing to render but an email parsed out of a JWT.
 *
 * <p>{@code tenantId} and {@code roles} describe the caller's <em>default</em> tenant — the one in the
 * token — not the tenant a given request is acting on via {@code X-Tenant-Id}. This endpoint answers "who
 * am I", not "where am I acting"; the client already knows the header it sent. Both are {@code null} and
 * empty respectively for a user who belongs to no tenant yet, or whose membership has been removed, so
 * the account menu still renders and the client can route them to the tenant picker.
 *
 * <p>The roles are read from the membership rather than from the token claim, for the reason stated in
 * API_REFERENCE.md: the claim is informational, and authorization re-reads membership on every request.
 */
public record MeResponse(
        String userId,
        String email,
        String firstName,
        String lastName,
        UserStatus status,
        String tenantId,
        List<String> roles
) {
}
