package com.feature_flag.platform.tenant.dto;

/**
 * The one response that carries a key in plaintext. Only its SHA-256 digest is stored, so this value
 * cannot be recovered afterwards — losing it means issuing a new key.
 */
public record CreatedApiKeyResponse(
        ApiKeyResponse apiKey,
        String key
) {
}
