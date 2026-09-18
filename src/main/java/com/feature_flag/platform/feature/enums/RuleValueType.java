package com.feature_flag.platform.feature.enums;

/**
 * Declared type of a targeting rule's value(s). Required because attributes are free-form strings
 * with no schema to infer type from — without an explicit type, comparisons like {@code appVersion GT 10}
 * are ambiguous across SDKs.
 */
public enum RuleValueType {
    STRING,
    NUMBER,
    BOOLEAN,
    SEMVER
}
