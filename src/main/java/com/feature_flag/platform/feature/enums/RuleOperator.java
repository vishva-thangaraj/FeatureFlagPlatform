package com.feature_flag.platform.feature.enums;

/**
 * Targeting comparison operators. Persisted via {@code @Enumerated(EnumType.STRING)} into a
 * {@code VARCHAR(20)} column — every name MUST stay <= 20 chars, since ddl-auto=update will not
 * widen the column. See TARGETING_PLAN.md (D9). {@code NOT_*} variants are intentionally absent;
 * use the per-rule {@code negated} flag instead (D7).
 */
public enum RuleOperator {
    EQUALS,
    NOT_EQUALS,
    IN,
    NOT_IN,
    GT,
    GTE,
    LT,
    LTE,
    CONTAINS,
    STARTS_WITH,
    ENDS_WITH,
    SEMVER_GT,
    SEMVER_GTE,
    SEMVER_LT,
    SEMVER_LTE
}