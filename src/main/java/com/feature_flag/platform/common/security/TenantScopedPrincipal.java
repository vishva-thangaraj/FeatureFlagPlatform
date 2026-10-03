package com.feature_flag.platform.common.security;

/**
 * What a dashboard user and an SDK key have in common: both act within exactly one tenant. Services
 * that serve both surfaces depend on this rather than on either concrete principal.
 */
public interface TenantScopedPrincipal {

    Long getTenantId();
}
