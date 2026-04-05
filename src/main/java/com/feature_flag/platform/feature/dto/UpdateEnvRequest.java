package com.feature_flag.platform.feature.dto;

public record UpdateEnvRequest(
        String name,
        String description
) {
}