package com.feature_flag.platform.common.security.config;

import java.util.List;

/**
 * The single list of request patterns that {@code SecurityConfig} lets through without authentication.
 *
 * <p>It lives here rather than inline in {@code SecurityConfig} so that
 * {@link PublicEndpointPolicyValidator} can check it against the {@code authRequired} flags in
 * {@code api-security.json} at startup. The two used to be independent copies of the same decision: adding
 * a pattern here without a matching {@code "authRequired": false} rule (or the reverse) changed who may
 * reach an endpoint with nothing anywhere objecting.
 */
public final class PublicEndpoints {

    private PublicEndpoints() {
    }

    /**
     * Ant patterns, in the same syntax {@code api-security.json} uses, so the two can be compared
     * literally. Every entry MUST appear in the policy with {@code "authRequired": false}.
     */
    public static final List<String> PATTERNS = List.of(
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            // Acceptance stays reachable without credentials only because an invitee who has no account
            // yet registers through it. That makes permitAll the *absence* of a check, not a decision that
            // anyone may accept: an unauthenticated request here still carries an
            // AnonymousAuthenticationToken, so InvitationServiceImpl authorizes acceptance for an
            // already-registered invitee by comparing the authenticated principal to the invited user, and
            // refuses anonymous and third-party callers there.
            "/api/v1/invitations/*/accept",
            "/api/v1/invitations/*/decline",
            "/api/v1/invitations/*");

    public static String[] patterns() {
        return PATTERNS.toArray(String[]::new);
    }
}
