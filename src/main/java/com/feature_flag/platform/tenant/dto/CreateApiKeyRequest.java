package com.feature_flag.platform.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * @param envName the environment this key may read; a key is bound to one and cannot be repointed
 * @param expiresAt optional, and must be in the future when given
 */
public record CreateApiKeyRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank String envName,
        Instant expiresAt
) {
}
