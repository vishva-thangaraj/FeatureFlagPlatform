package com.feature_flag.platform.feature.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateFeatureRequest(
        @NotBlank @Size(max = 50) String key,
        String description
) {
}