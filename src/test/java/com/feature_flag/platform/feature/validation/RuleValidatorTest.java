package com.feature_flag.platform.feature.validation;

import com.feature_flag.platform.common.config.I18N;
import com.feature_flag.platform.common.exception.BadRequestException;
import com.feature_flag.platform.common.exception.ConflictException;
import com.feature_flag.platform.feature.dto.RuleRequest;
import com.feature_flag.platform.feature.enums.RuleOperator;
import com.feature_flag.platform.feature.enums.RuleValueType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

class RuleValidatorTest {

    private final RuleValidator validator = new RuleValidator();
    private MockedStatic<I18N> i18nMock;

    @BeforeEach
    void setUp() {
        i18nMock = mockStatic(I18N.class);
        i18nMock.when(() -> I18N.getMessage(anyString())).thenAnswer(inv -> inv.getArgument(0));
        i18nMock.when(() -> I18N.getMessage(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        i18nMock.close();
    }

    private RuleRequest rule(String attribute, RuleOperator op, RuleValueType type, List<Object> values) {
        return new RuleRequest(attribute, op, type, values, false, false, true, 0);
    }

    @Test
    void equalsWithSingleStringValueIsValid() {
        assertDoesNotThrow(() -> validator.validate(
                rule("country", RuleOperator.EQUALS, RuleValueType.STRING, List.of("IN"))));
    }

    @Test
    void equalsWithMoreThanOneValueIsRejected() {
        assertThrows(BadRequestException.class, () -> validator.validate(
                rule("country", RuleOperator.EQUALS, RuleValueType.STRING, List.of("IN", "US"))));
    }

    @Test
    void inWithMultipleValuesIsValid() {
        assertDoesNotThrow(() -> validator.validate(
                rule("country", RuleOperator.IN, RuleValueType.STRING, List.of("IN", "US", "GB"))));
    }

    @Test
    void emptyValuesIsRejected() {
        assertThrows(BadRequestException.class, () -> validator.validate(
                rule("country", RuleOperator.IN, RuleValueType.STRING, List.of())));
    }

    @Test
    void numericOperatorRequiresNumberType() {
        assertThrows(BadRequestException.class, () -> validator.validate(
                rule("age", RuleOperator.GT, RuleValueType.STRING, List.of("18"))));
        assertDoesNotThrow(() -> validator.validate(
                rule("age", RuleOperator.GT, RuleValueType.NUMBER, List.of(18))));
    }

    @Test
    void semverOperatorRequiresSemverType() {
        assertThrows(BadRequestException.class, () -> validator.validate(
                rule("appVersion", RuleOperator.SEMVER_GTE, RuleValueType.STRING, List.of("5.2.0"))));
        assertDoesNotThrow(() -> validator.validate(
                rule("appVersion", RuleOperator.SEMVER_GTE, RuleValueType.SEMVER, List.of("5.2.0"))));
    }

    @Test
    void malformedSemverValueIsRejected() {
        assertThrows(BadRequestException.class, () -> validator.validate(
                rule("appVersion", RuleOperator.SEMVER_GTE, RuleValueType.SEMVER, List.of("five.two"))));
    }

    @Test
    void booleanTypeOnlyAllowsEquality() {
        assertThrows(BadRequestException.class, () -> validator.validate(
                rule("beta", RuleOperator.GT, RuleValueType.BOOLEAN, List.of(true))));
        assertDoesNotThrow(() -> validator.validate(
                rule("beta", RuleOperator.EQUALS, RuleValueType.BOOLEAN, List.of(true))));
    }

    @Test
    void valueNotMatchingDeclaredTypeIsRejected() {
        assertThrows(BadRequestException.class, () -> validator.validate(
                rule("plan", RuleOperator.EQUALS, RuleValueType.NUMBER, List.of("FREE"))));
    }

    @Test
    void duplicateSingleValuedAttributeOperatorIsRejected() {
        // country EQUALS "IN" AND country EQUALS "US" can never both match — the flag would be dead.
        List<RuleRequest> rules = List.of(
                rule("country", RuleOperator.EQUALS, RuleValueType.STRING, List.of("IN")),
                rule("country", RuleOperator.EQUALS, RuleValueType.STRING, List.of("US")));
        assertThrows(ConflictException.class, () -> validator.validateSet(rules));
    }

    @Test
    void sameAttributeDifferentOperatorsIsAllowed() {
        List<RuleRequest> rules = List.of(
                rule("country", RuleOperator.IN, RuleValueType.STRING, List.of("IN", "US")),
                rule("plan", RuleOperator.EQUALS, RuleValueType.STRING, List.of("FREE")));
        assertDoesNotThrow(() -> validator.validateSet(rules));
    }

    @Test
    void disabledDuplicateIsIgnored() {
        RuleRequest enabled = rule("country", RuleOperator.EQUALS, RuleValueType.STRING, List.of("IN"));
        RuleRequest disabled = new RuleRequest("country", RuleOperator.EQUALS, RuleValueType.STRING,
                List.of("US"), false, false, false, 0);
        assertDoesNotThrow(() -> validator.validateSet(List.of(enabled, disabled)));
    }
}
