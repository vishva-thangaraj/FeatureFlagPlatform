package com.feature_flag.platform.feature.dto;

import jakarta.validation.constraints.NotNull;

public record FeatureEnableRequest(
        @NotNull Long envId,
        @NotNull Boolean enabled
) {
}