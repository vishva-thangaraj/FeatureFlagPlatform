package com.feature_flag.platform.feature.dto;

import jakarta.validation.constraints.NotNull;

public record FeatureUserOverrideRequest(
        @NotNull Boolean enabled
) {
}