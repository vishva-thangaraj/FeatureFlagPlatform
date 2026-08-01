package com.feature_flag.platform.feature.dto;

import java.time.Instant;
import java.util.List;

/**
 * The targeting configuration for a single flag in one environment. The platform does not evaluate;
 * the SDK receives this and computes on/off client-side per the contract in API_REFERENCE.md.
 * Disabled rules are omitted (D8) — clients never see config they must know to ignore.
 */
public record FlagConfigResponse(
        String featureKey,
        String environment,
        boolean enabled,
        int rolloutPercentage,
        List<String> enabledUsers,
        List<String> disabledUsers,
        List<RuleResponse> rules,
        Instant lastModified
) {
}
