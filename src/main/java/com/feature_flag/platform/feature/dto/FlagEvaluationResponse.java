package com.feature_flag.platform.feature.dto;

public record FlagEvaluationResponse(
        String featureKey,
        boolean enabled
) {
}