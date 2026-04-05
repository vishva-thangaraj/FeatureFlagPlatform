package com.feature_flag.platform.feature.dto;

import java.util.List;

public record FeatureListResponse(
        List<FeatureResponse> features
) {
}