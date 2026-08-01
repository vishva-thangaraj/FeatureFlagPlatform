package com.feature_flag.platform.feature.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record OverrideRequest(
        @NotBlank @Size(max = 255) String externalUserId,
        @NotNull Boolean enabled
) {
}
