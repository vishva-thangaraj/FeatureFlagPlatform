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

import java.util.ArrayList;
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

    private RuleRequest negated(String attribute, RuleOperator op, RuleValueType type, List<Object> values) {
        return new RuleRequest(attribute, op, type, values, false, true, true, 0);
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

    // ------------------------------------------------------------------ satisfiable sets
    //
    // The old check keyed on (attribute, operator) for every single-valued operator and ignored
    // `negated` entirely, so each of the sets below was rejected despite being perfectly satisfiable.

    @Test
    void twoContainsRulesOnOneAttributeAreAllowed() {
        // "probeta" satisfies both. The old error even suggested IN, which is exact-match and cannot
        // express "contains this AND contains that" at all.
        assertDoesNotThrow(() -> validator.validateSet(List.of(
                rule("name", RuleOperator.CONTAINS, RuleValueType.STRING, List.of("pro")),
                rule("name", RuleOperator.CONTAINS, RuleValueType.STRING, List.of("beta")))));
    }

    @Test
    void twoNotEqualsRulesOnOneAttributeAreAllowed() {
        // country != US AND country != CA is satisfied by every other country.
        assertDoesNotThrow(() -> validator.validateSet(List.of(
                rule("country", RuleOperator.NOT_EQUALS, RuleValueType.STRING, List.of("US")),
                rule("country", RuleOperator.NOT_EQUALS, RuleValueType.STRING, List.of("CA")))));
    }

    @Test
    void equalsRulesDifferingOnlyByNegationAreAllowed() {
        // plan == PRO AND NOT(plan == FREE): both hold for a PRO account.
        assertDoesNotThrow(() -> validator.validateSet(List.of(
                rule("plan", RuleOperator.EQUALS, RuleValueType.STRING, List.of("PRO")),
                negated("plan", RuleOperator.EQUALS, RuleValueType.STRING, List.of("FREE")))));
    }

    @Test
    void sameDirectionComparisonsAreAllowed() {
        // age > 5 AND age > 10 simply means age > 10.
        assertDoesNotThrow(() -> validator.validateSet(List.of(
                rule("age", RuleOperator.GT, RuleValueType.NUMBER, List.of(5)),
                rule("age", RuleOperator.GT, RuleValueType.NUMBER, List.of(10)))));
    }

    @Test
    void overlappingRangeIsAllowed() {
        assertDoesNotThrow(() -> validator.validateSet(List.of(
                rule("age", RuleOperator.GTE, RuleValueType.NUMBER, List.of(18)),
                rule("age", RuleOperator.LT, RuleValueType.NUMBER, List.of(65)))));
    }

    @Test
    void compatiblePrefixesAreAllowed() {
        // Anything starting with "pro" also starts with "p".
        assertDoesNotThrow(() -> validator.validateSet(List.of(
                rule("name", RuleOperator.STARTS_WITH, RuleValueType.STRING, List.of("pro")),
                rule("name", RuleOperator.STARTS_WITH, RuleValueType.STRING, List.of("p")))));
    }

    @Test
    void overlappingSemverRangeIsAllowed() {
        assertDoesNotThrow(() -> validator.validateSet(List.of(
                rule("appVersion", RuleOperator.SEMVER_GTE, RuleValueType.SEMVER, List.of("4.0.0")),
                rule("appVersion", RuleOperator.SEMVER_LT, RuleValueType.SEMVER, List.of("5.0.0")))));
    }

    // ------------------------------------------------------------------ genuinely contradictory sets

    @Test
    void equalsAndItsOwnNegationOnTheSameValueIsRejected() {
        assertThrows(ConflictException.class, () -> validator.validateSet(List.of(
                rule("plan", RuleOperator.EQUALS, RuleValueType.STRING, List.of("PRO")),
                negated("plan", RuleOperator.EQUALS, RuleValueType.STRING, List.of("PRO")))));
    }

    @Test
    void equalsAndNotEqualsOnTheSameValueIsRejected() {
        assertThrows(ConflictException.class, () -> validator.validateSet(List.of(
                rule("plan", RuleOperator.EQUALS, RuleValueType.STRING, List.of("PRO")),
                rule("plan", RuleOperator.NOT_EQUALS, RuleValueType.STRING, List.of("PRO")))));
    }

    @Test
    void emptyNumericRangeIsRejected() {
        assertThrows(ConflictException.class, () -> validator.validateSet(List.of(
                rule("age", RuleOperator.GT, RuleValueType.NUMBER, List.of(10)),
                rule("age", RuleOperator.LT, RuleValueType.NUMBER, List.of(5)))));
    }

    @Test
    void emptySemverRangeIsRejected() {
        assertThrows(ConflictException.class, () -> validator.validateSet(List.of(
                rule("appVersion", RuleOperator.SEMVER_GTE, RuleValueType.SEMVER, List.of("5.0.0")),
                rule("appVersion", RuleOperator.SEMVER_LT, RuleValueType.SEMVER, List.of("4.2.0")))));
    }

    @Test
    void incompatiblePrefixesAreRejected() {
        assertThrows(ConflictException.class, () -> validator.validateSet(List.of(
                rule("name", RuleOperator.STARTS_WITH, RuleValueType.STRING, List.of("pro")),
                rule("name", RuleOperator.STARTS_WITH, RuleValueType.STRING, List.of("pre")))));
    }

    @Test
    void containsAndItsNegationOnTheSameValueIsRejected() {
        assertThrows(ConflictException.class, () -> validator.validateSet(List.of(
                rule("name", RuleOperator.CONTAINS, RuleValueType.STRING, List.of("pro")),
                negated("name", RuleOperator.CONTAINS, RuleValueType.STRING, List.of("pro")))));
    }

    @Test
    void rulesOnDifferentAttributesNeverContradict() {
        assertDoesNotThrow(() -> validator.validateSet(List.of(
                rule("country", RuleOperator.EQUALS, RuleValueType.STRING, List.of("IN")),
                rule("region", RuleOperator.EQUALS, RuleValueType.STRING, List.of("US")))));
    }

    // ------------------------------------------------------------------ null entries

    @Test
    void nullRuleInTheSetIsABadRequest() {
        // Bean Validation cascades @Valid into the list but has no opinion on a null element, so
        // {"rules":[null]} arrives here intact and used to NPE its way into a 500.
        List<RuleRequest> withNull = new ArrayList<>();
        withNull.add(rule("country", RuleOperator.EQUALS, RuleValueType.STRING, List.of("IN")));
        withNull.add(null);
        assertThrows(BadRequestException.class, () -> validator.validateSet(withNull));
    }

    @Test
    void nullRuleOnItsOwnIsABadRequest() {
        assertThrows(BadRequestException.class, () -> validator.validate(null));
    }
}
