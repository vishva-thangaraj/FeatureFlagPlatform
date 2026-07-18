package com.feature_flag.platform.feature.dto;

import com.feature_flag.platform.feature.enums.RuleOperator;

public record RuleResponse(
        String attribute,
        RuleOperator operator,
        Object value
) {
}