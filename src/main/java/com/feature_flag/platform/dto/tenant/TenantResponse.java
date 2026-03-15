package com.feature_flag.platform.dto.tenant;

import com.feature_flag.platform.entity.Role;
import com.feature_flag.platform.enums.TenantPlan;

import java.util.List;

public record TenantResponse(
        String id,
        String name,
        TenantPlan plan,
        List<Role> roles
) {
}
