package com.feature_flag.platform.feature.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record FeatureEnableRequest(
        @NotNull Long envId,
        @NotNull Boolean enabled,
        @Min(0) @Max(100) Integer rolloutPercentage
) {
}