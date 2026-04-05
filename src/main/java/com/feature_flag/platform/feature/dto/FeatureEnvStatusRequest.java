package com.feature_flag.platform.feature.dto;

import jakarta.validation.constraints.NotNull;

public record FeatureEnvStatusRequest(
        @NotNull Boolean enabled
) {
}