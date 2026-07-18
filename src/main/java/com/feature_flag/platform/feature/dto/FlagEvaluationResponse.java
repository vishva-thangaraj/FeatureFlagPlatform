package com.feature_flag.platform.feature.dto;

import java.util.List;

public record FlagEvaluationResponse(
        String featureKey,
        String environment,
        boolean enabled,
        int rolloutPercentage,
        List<String> targetUsers,
        List<RuleResponse> rules
) {
}