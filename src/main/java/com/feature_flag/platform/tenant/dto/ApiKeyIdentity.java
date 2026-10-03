package com.feature_flag.platform.tenant.dto;

/**
 * Everything authentication needs from a key, as a projection: the filter runs outside any transaction,
 * so loading the entity would mean either eager joins or a lazy-initialisation failure on env.name.
 */
public record ApiKeyIdentity(Long apiKeyId, Long tenantId, Long envId, String envName, String prefix) {
}
