package com.feature_flag.platform.feature.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Replace the entire rule set for a feature/env in one call. An empty list clears all rules. */
public record ReplaceRulesRequest(
        @NotNull @Valid List<RuleRequest> rules
) {
}
