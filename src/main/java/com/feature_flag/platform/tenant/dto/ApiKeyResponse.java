package com.feature_flag.platform.tenant.dto;

import java.time.Instant;

/**
 * An API key as the dashboard sees it. Carries the non-secret prefix and never the key: the key exists
 * in plaintext only in the response to its own creation.
 *
 * <p>{@code id} is a string because it is a TSID — around 8.9e17, far past the 2^53 where a JavaScript
 * {@code Number} stops being exact. Sent as a JSON number it is silently rounded on arrival, and the
 * id the dashboard sends back to revoke a key is then not the id of any key.
 */
public record ApiKeyResponse(
        String id,
        String name,
        String envName,
        String prefix,
        Instant createdAt,
        Instant lastUsedAt,
        Instant expiresAt,
        Instant revokedAt
) {
}
