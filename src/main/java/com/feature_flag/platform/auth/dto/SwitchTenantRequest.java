package com.feature_flag.platform.auth.dto;

import jakarta.validation.constraints.NotNull;

public record SwitchTenantRequest(
        @NotNull Long tenantId
) {
}