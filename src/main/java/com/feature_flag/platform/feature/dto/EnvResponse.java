package com.feature_flag.platform.feature.dto;

import java.time.Instant;

public record EnvResponse(
        String id,
        String name,
        String description,
        Instant createdAt
) {
}