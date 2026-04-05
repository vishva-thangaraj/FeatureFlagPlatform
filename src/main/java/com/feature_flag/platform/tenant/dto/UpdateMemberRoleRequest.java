package com.feature_flag.platform.tenant.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateMemberRoleRequest(
        @NotBlank String roleName
) {
}