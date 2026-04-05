package com.feature_flag.platform.feature.dto;

public record FeatureUserOverrideResponse(
        String id,
        String externalUserId,
        boolean enabled
) {
}