package com.feature_flag.platform.feature.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateEnvRequest(
        @NotBlank String name,
        String description
) {
}