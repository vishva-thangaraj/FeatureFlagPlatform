package com.feature_flag.platform.dto.auth;

import java.util.List;

public record UserTenantsResponse(
        Long userId,
        String email,
        List<TenantInfo> tenants
) {
    public record TenantInfo(Long tenantId, String tenantName, List<String> roles) {
    }
}
