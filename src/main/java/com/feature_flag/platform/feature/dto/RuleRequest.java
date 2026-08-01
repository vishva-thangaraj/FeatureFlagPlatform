package com.feature_flag.platform.feature.dto;

import com.feature_flag.platform.feature.enums.RuleOperator;
import com.feature_flag.platform.feature.enums.RuleValueType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RuleRequest(
        @NotBlank @Size(max = 100)
        @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_.]{0,99}$",
                message = "must start with a letter and contain only letters, digits, '_' or '.'")
        String attribute,

        @NotNull RuleOperator operator,
        @NotNull RuleValueType valueType,
        @NotEmpty List<Object> values,
        boolean caseSensitive,
        boolean negated,
        boolean enabled,
        int priority
) {
}
