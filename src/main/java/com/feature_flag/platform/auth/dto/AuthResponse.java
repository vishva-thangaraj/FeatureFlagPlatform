package com.feature_flag.platform.auth.dto;

import java.util.List;

/**
 * The result of every call that establishes or re-establishes a session.
 *
 * <p>{@code userId} and {@code tenantId} are strings because they are TSIDs — around 4.6e17, well past
 * the 2^53 where a JavaScript {@code Number} stops being exact. Sent as JSON numbers they are silently
 * rounded on arrival, so the tenant id a browser reads back from a login is the id of no tenant, and
 * every subsequent request carries an {@code X-Tenant-Id} that resolves to nothing. {@code TenantResponse}
 * and {@code ApiKeyResponse} already made this choice; this record now matches them.
 *
 * <p>Request bodies are unaffected: {@code LoginRequest.tenantId} and {@code SwitchTenantRequest.tenantId}
 * stay {@code Long}, because Jackson accepts a quoted number for a numeric target.
 */
public record AuthResponse(
        String accessToken,
        String refreshToken,
        String userId,
        String email,
        String tenantId,
        List<String> roles,
        long accessTokenExpiresIn
) {
}
