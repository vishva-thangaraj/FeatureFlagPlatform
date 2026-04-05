package com.feature_flag.platform.feature.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record BulkEvaluationRequest(
        @NotEmpty List<String> featureKeys,
        @NotBlank String envName
) {
}