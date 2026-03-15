package com.feature_flag.platform.dto.auth;

import jakarta.validation.constraints.NotNull;

public record SwitchTenantRequest(
        @NotNull Long tenantId
) {
}
