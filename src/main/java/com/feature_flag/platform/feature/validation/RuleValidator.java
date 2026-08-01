package com.feature_flag.platform.feature.validation;

import com.feature_flag.platform.common.config.I18N;
import com.feature_flag.platform.common.config.Messages;
import com.feature_flag.platform.common.exception.BadRequestException;
import com.feature_flag.platform.common.exception.ConflictException;
import com.feature_flag.platform.feature.dto.RuleRequest;
import com.feature_flag.platform.feature.enums.RuleOperator;
import com.feature_flag.platform.feature.enums.RuleValueType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates targeting rules at write time. In a client-evaluated design the platform is the only
 * place that can reject config that no SDK will ever be able to evaluate — see TARGETING_PLAN.md §2.
 */
@Component
public class RuleValidator {

    /** Operators that take exactly one value. IN / NOT_IN are the only multi-value operators. */
    private static final Set<RuleOperator> SINGLE_VALUE = EnumSet.of(
            RuleOperator.EQUALS, RuleOperator.NOT_EQUALS,
            RuleOperator.GT, RuleOperator.GTE, RuleOperator.LT, RuleOperator.LTE,
            RuleOperator.CONTAINS, RuleOperator.STARTS_WITH, RuleOperator.ENDS_WITH,
            RuleOperator.SEMVER_GT, RuleOperator.SEMVER_GTE, RuleOperator.SEMVER_LT, RuleOperator.SEMVER_LTE);

    private static final Set<RuleOperator> NUMERIC_ONLY = EnumSet.of(
            RuleOperator.GT, RuleOperator.GTE, RuleOperator.LT, RuleOperator.LTE);

    private static final Set<RuleOperator> STRING_ONLY = EnumSet.of(
            RuleOperator.CONTAINS, RuleOperator.STARTS_WITH, RuleOperator.ENDS_WITH);

    private static final Set<RuleOperator> SEMVER_ONLY = EnumSet.of(
            RuleOperator.SEMVER_GT, RuleOperator.SEMVER_GTE, RuleOperator.SEMVER_LT, RuleOperator.SEMVER_LTE);

    private static final Pattern SEMVER = Pattern.compile("^\\d+(\\.\\d+){0,3}([-+].*)?$");

    /** Validate a single rule's operator/arity/type consistency. */
    public void validate(RuleRequest rule) {
        List<Object> values = rule.values();
        if (values == null || values.isEmpty()) {
            throw new BadRequestException(
                    I18N.getMessage(Messages.FEATURE_RULE_OPERATOR_VALUE_MISMATCH, rule.operator(), "at least one"));
        }
        if (SINGLE_VALUE.contains(rule.operator()) && values.size() != 1) {
            throw new BadRequestException(
                    I18N.getMessage(Messages.FEATURE_RULE_OPERATOR_VALUE_MISMATCH, rule.operator(), "exactly one"));
        }

        RuleValueType type = rule.valueType();
        // Operator/type compatibility.
        if (NUMERIC_ONLY.contains(rule.operator()) && type != RuleValueType.NUMBER) {
            throw new BadRequestException(I18N.getMessage(Messages.FEATURE_RULE_VALUE_TYPE_MISMATCH, RuleValueType.NUMBER));
        }
        if (STRING_ONLY.contains(rule.operator()) && type != RuleValueType.STRING) {
            throw new BadRequestException(I18N.getMessage(Messages.FEATURE_RULE_VALUE_TYPE_MISMATCH, RuleValueType.STRING));
        }
        if (SEMVER_ONLY.contains(rule.operator()) && type != RuleValueType.SEMVER) {
            throw new BadRequestException(I18N.getMessage(Messages.FEATURE_RULE_VALUE_TYPE_MISMATCH, RuleValueType.SEMVER));
        }
        if (type == RuleValueType.BOOLEAN
                && rule.operator() != RuleOperator.EQUALS && rule.operator() != RuleOperator.NOT_EQUALS) {
            throw new BadRequestException(I18N.getMessage(Messages.FEATURE_RULE_VALUE_TYPE_MISMATCH, RuleValueType.BOOLEAN));
        }

        // Each value must match the declared type.
        for (Object value : values) {
            if (!matchesType(value, type)) {
                throw new BadRequestException(I18N.getMessage(Messages.FEATURE_RULE_VALUE_TYPE_MISMATCH, type));
            }
        }
    }

    /**
     * Validate a whole rule set together. Rejects a second single-valued rule on the same
     * (attribute, operator), which under the all-ANDed model would make the flag permanently
     * unmatchable with no error surfaced anywhere (TARGETING_PLAN.md §2).
     */
    public void validateSet(List<RuleRequest> rules) {
        if (rules == null) {
            return;
        }
        List<RuleRequest> enabled = new ArrayList<>();
        for (RuleRequest r : rules) {
            validate(r);
            if (r.enabled()) {
                enabled.add(r);
            }
        }
        Set<String> seen = new HashSet<>();
        for (RuleRequest r : enabled) {
            if (!SINGLE_VALUE.contains(r.operator())) {
                continue;
            }
            String key = r.attribute() + "|" + r.operator();
            if (!seen.add(key)) {
                throw new ConflictException(
                        I18N.getMessage(Messages.FEATURE_RULE_DUPLICATE_ATTRIBUTE, r.attribute(), r.operator()));
            }
        }
    }

    private boolean matchesType(Object value, RuleValueType type) {
        return switch (type) {
            case STRING -> value instanceof String;
            case NUMBER -> value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
            case SEMVER -> value instanceof String s && SEMVER.matcher(s).matches();
        };
    }
}
