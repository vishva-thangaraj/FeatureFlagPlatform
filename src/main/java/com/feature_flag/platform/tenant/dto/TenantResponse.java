package com.feature_flag.platform.tenant.dto;

import com.feature_flag.platform.tenant.enums.TenantPlan;

import java.util.List;

public record TenantResponse(
        String id,
        String name,
        TenantPlan plan,
        List<RoleResponse> roles
) {
}