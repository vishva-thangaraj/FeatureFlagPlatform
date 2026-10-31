package com.feature_flag.platform.feature.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record FeatureResponse(
        String id,
        String key,
        @Schema(nullable = true) String description,
        Instant createdAt
) {
}