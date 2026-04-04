package com.feature_flag.platform.tenant.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record UserInviteRequest(
        @NotBlank @Email String email,
        @NotBlank String role
) {
}