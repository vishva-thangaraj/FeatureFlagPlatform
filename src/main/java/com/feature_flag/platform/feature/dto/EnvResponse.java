package com.feature_flag.platform.feature.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record EnvResponse(
        String id,
        String name,
        @Schema(nullable = true) String description,
        Instant createdAt
) {
}