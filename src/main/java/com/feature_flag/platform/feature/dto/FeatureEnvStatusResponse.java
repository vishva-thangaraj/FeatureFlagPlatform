package com.feature_flag.platform.feature.dto;

public record FeatureEnvStatusResponse(
        String id,
        String featureId,
        String envId,
        String envName,
        boolean enabled
) {
}