package com.feature_flag.platform.feature.dto;

import java.time.Instant;

public record FeatureResponse(
        String id,
        String key,
        String description,
        Instant createdAt
) {
}