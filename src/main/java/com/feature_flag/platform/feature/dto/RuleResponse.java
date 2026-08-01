package com.feature_flag.platform.feature.dto;

import com.feature_flag.platform.feature.enums.RuleOperator;
import com.feature_flag.platform.feature.enums.RuleValueType;

import java.util.List;

public record RuleResponse(
        String id,
        String attribute,
        RuleOperator operator,
        RuleValueType valueType,
        List<Object> values,
        boolean caseSensitive,
        boolean negated,
        boolean enabled,
        int priority
) {
}