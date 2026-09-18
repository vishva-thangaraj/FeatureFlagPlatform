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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates targeting rules at write time. In a client-evaluated design the platform is the only
 * place that can reject config that no SDK will ever be able to evaluate.
 */
@Component
public class RuleValidator {

    /**
     * Message key for a null element inside the rules array. Declared here rather than in
     * {@code Messages} because the shared constants class is owned elsewhere; the property itself
     * lives in {@code messages.properties} like every other.
     */
    private static final String FEATURE_RULE_NULL_ENTRY = "feature.rule.null.entry";

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

    private static final Set<RuleOperator> EQUALITY = EnumSet.of(RuleOperator.EQUALS, RuleOperator.NOT_EQUALS);

    private static final Pattern SEMVER = Pattern.compile("^\\d+(\\.\\d+){0,3}([-+].*)?$");

    /** Validate a single rule's operator/arity/type consistency. */
    public void validate(RuleRequest rule) {
        requireNonNullRule(rule);
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
     * Validate a whole rule set together. Every enabled rule is ANDed, so a set whose conjunction is
     * unsatisfiable produces a flag that can never match, with no error surfaced anywhere at
     * evaluation time — the SDK simply returns false forever. Only genuinely contradictory
     * combinations are rejected here.
     *
     * <p>This used to key on {@code attribute + "|" + operator} for every single-valued operator,
     * which rejected plenty of perfectly satisfiable sets: {@code name CONTAINS "pro"} together with
     * {@code name CONTAINS "beta"} (a string can contain both), {@code country NOT_EQUALS "US"} with
     * {@code NOT_EQUALS "CA"} (anything outside both satisfies it), two rules differing only by
     * {@code negated}, and {@code age GT 5} with {@code age GT 10}. It also ignored {@code negated}
     * entirely, and its advice — "use IN" — is wrong for substring and range operators, which IN
     * (exact match) cannot express.
     */
    public void validateSet(List<RuleRequest> rules) {
        if (rules == null) {
            return;
        }
        List<RuleRequest> enabled = new ArrayList<>();
        for (RuleRequest r : rules) {
            requireNonNullRule(r);
            validate(r);
            if (r.enabled()) {
                enabled.add(r);
            }
        }

        // Rules on different attributes can never contradict one another.
        Map<String, List<RuleRequest>> byAttribute = new LinkedHashMap<>();
        for (RuleRequest r : enabled) {
            byAttribute.computeIfAbsent(r.attribute(), k -> new ArrayList<>()).add(r);
        }
        for (Map.Entry<String, List<RuleRequest>> entry : byAttribute.entrySet()) {
            checkSatisfiable(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Bean Validation cascades {@code @Valid} into a collection's elements but has no opinion on a
     * {@code null} element, so {@code {"rules":[null]}} arrives here intact. Dereferencing it later
     * is an NPE, which the handler can only turn into a 500 — the caller's malformed body reported
     * as a server fault.
     */
    private void requireNonNullRule(RuleRequest rule) {
        if (rule == null) {
            throw new BadRequestException(I18N.getMessage(FEATURE_RULE_NULL_ENTRY));
        }
    }

    // ------------------------------------------------------------------ satisfiability

    private void checkSatisfiable(String attribute, List<RuleRequest> rules) {
        for (int i = 0; i < rules.size(); i++) {
            for (int j = i + 1; j < rules.size(); j++) {
                String reason = pairwiseContradiction(rules.get(i), rules.get(j));
                if (reason != null) {
                    throw new ConflictException(
                            I18N.getMessage(Messages.FEATURE_RULE_DUPLICATE_ATTRIBUTE, attribute, reason));
                }
            }
        }
        checkRange(attribute, rules, NUMERIC_ONLY, RuleValueType.NUMBER);
        checkRange(attribute, rules, SEMVER_ONLY, RuleValueType.SEMVER);
    }

    /**
     * Contradictions detectable from a single pair of rules. Returns a human-readable reason, or
     * {@code null} when the two can both be satisfied.
     */
    private String pairwiseContradiction(RuleRequest a, RuleRequest b) {
        if (a.valueType() != b.valueType()) {
            // Different declared types on one attribute: the SDK compares them differently, and we
            // cannot reason about the intersection. Not our call to reject.
            return null;
        }
        if (EQUALITY.contains(a.operator()) && EQUALITY.contains(b.operator())) {
            return equalityContradiction(a, b);
        }
        if (a.operator() == b.operator() && STRING_ONLY.contains(a.operator())) {
            return stringContradiction(a, b);
        }
        return null;
    }

    /**
     * {@code x = p} and {@code x = q} with p != q can never both hold; neither can {@code x = p} and
     * {@code x != p}. Two "not equal" rules, by contrast, are satisfied by any third value, and
     * repeating the same equality is merely redundant.
     */
    private String equalityContradiction(RuleRequest a, RuleRequest b) {
        boolean aWantsEqual = wantsMatch(a, a.operator() == RuleOperator.EQUALS);
        boolean bWantsEqual = wantsMatch(b, b.operator() == RuleOperator.EQUALS);
        boolean sameValue = valuesEqual(a, b);

        if (aWantsEqual && bWantsEqual && !sameValue) {
            return describe(a) + " and " + describe(b) + " — a value cannot equal both";
        }
        if (aWantsEqual != bWantsEqual && sameValue) {
            return describe(a) + " and " + describe(b) + " — one requires the value the other forbids";
        }
        return null;
    }

    /**
     * CONTAINS / STARTS_WITH / ENDS_WITH. Two positive substring rules are almost always satisfiable
     * ("pro" and "beta" both fit in "probeta"); the exception is a prefix or suffix constraint whose
     * two values disagree at a position they share. A positive rule contradicts a negated one when
     * the positive one implies the negated one's match.
     */
    private String stringContradiction(RuleRequest a, RuleRequest b) {
        String left = asString(a);
        String right = asString(b);
        if (left == null || right == null) {
            return null;
        }
        boolean caseSensitive = a.caseSensitive() && b.caseSensitive();
        if (!caseSensitive) {
            left = left.toLowerCase();
            right = right.toLowerCase();
        }
        boolean aPositive = wantsMatch(a, true);
        boolean bPositive = wantsMatch(b, true);
        RuleOperator op = a.operator();

        if (aPositive && bPositive) {
            if (op == RuleOperator.CONTAINS) {
                return null; // any two substrings can coexist in one longer string
            }
            boolean compatible = op == RuleOperator.STARTS_WITH
                    ? left.startsWith(right) || right.startsWith(left)
                    : left.endsWith(right) || right.endsWith(left);
            return compatible ? null
                    : describe(a) + " and " + describe(b) + " — no value can satisfy both";
        }
        if (aPositive == bPositive) {
            return null; // two negations are satisfied by anything matching neither
        }

        // One positive, one negated: contradictory when matching the positive one forces a match on
        // the negated one (e.g. STARTS_WITH "pro" always also STARTS_WITH "p").
        String positive = aPositive ? left : right;
        String negatedValue = aPositive ? right : left;
        boolean implied = switch (op) {
            case STARTS_WITH -> positive.startsWith(negatedValue);
            case ENDS_WITH -> positive.endsWith(negatedValue);
            case CONTAINS -> positive.contains(negatedValue);
            default -> false;
        };
        return implied
                ? describe(a) + " and " + describe(b) + " — one always matches what the other excludes"
                : null;
    }

    /**
     * Comparison operators describe an interval. Several of them on one attribute are fine as long as
     * the intersection is non-empty: {@code age GT 5} and {@code age GT 10} simply means {@code > 10}.
     * Only an empty intersection ({@code age GT 10} with {@code age LT 5}) is a contradiction.
     */
    private void checkRange(String attribute, List<RuleRequest> rules,
                            Set<RuleOperator> family, RuleValueType type) {
        Bound lower = null;
        Bound upper = null;
        for (RuleRequest r : rules) {
            if (!family.contains(r.operator()) || r.valueType() != type) {
                continue;
            }
            Comparable<Object> value = comparableValue(r, type);
            if (value == null) {
                continue;
            }
            // A negated comparison is the complement of itself: NOT(x > v) is x <= v.
            RuleOperator effective = r.negated() ? complement(r.operator()) : r.operator();
            boolean inclusive = effective == RuleOperator.GTE || effective == RuleOperator.LTE
                    || effective == RuleOperator.SEMVER_GTE || effective == RuleOperator.SEMVER_LTE;
            boolean isLower = effective == RuleOperator.GT || effective == RuleOperator.GTE
                    || effective == RuleOperator.SEMVER_GT || effective == RuleOperator.SEMVER_GTE;

            Bound bound = new Bound(value, inclusive, r);
            if (isLower) {
                lower = tighter(lower, bound, true);
            } else {
                upper = tighter(upper, bound, false);
            }
        }

        if (lower == null || upper == null) {
            return;
        }
        int cmp = lower.value.compareTo(upper.value);
        boolean empty = cmp > 0 || (cmp == 0 && !(lower.inclusive && upper.inclusive));
        if (empty) {
            String reason = describe(lower.rule) + " and " + describe(upper.rule)
                    + " — the ranges do not overlap";
            throw new ConflictException(
                    I18N.getMessage(Messages.FEATURE_RULE_DUPLICATE_ATTRIBUTE, attribute, reason));
        }
    }

    private Bound tighter(Bound current, Bound candidate, boolean lowerBound) {
        if (current == null) {
            return candidate;
        }
        int cmp = candidate.value.compareTo(current.value);
        if (cmp == 0) {
            return candidate.inclusive ? current : candidate; // exclusive is the tighter of the two
        }
        boolean candidateTighter = lowerBound ? cmp > 0 : cmp < 0;
        return candidateTighter ? candidate : current;
    }

    private RuleOperator complement(RuleOperator operator) {
        return switch (operator) {
            case GT -> RuleOperator.LTE;
            case GTE -> RuleOperator.LT;
            case LT -> RuleOperator.GTE;
            case LTE -> RuleOperator.GT;
            case SEMVER_GT -> RuleOperator.SEMVER_LTE;
            case SEMVER_GTE -> RuleOperator.SEMVER_LT;
            case SEMVER_LT -> RuleOperator.SEMVER_GTE;
            case SEMVER_LTE -> RuleOperator.SEMVER_GT;
            default -> operator;
        };
    }

    private record Bound(Comparable<Object> value, boolean inclusive, RuleRequest rule) {
    }

    // ------------------------------------------------------------------ value helpers

    /**
     * {@code negated} flips whatever the operator asserts: {@code NOT_EQUALS} with {@code negated}
     * means "equals", and a negated {@code STARTS_WITH} means "does not start with".
     */
    private boolean wantsMatch(RuleRequest rule, boolean operatorAsserts) {
        return rule.negated() != operatorAsserts;
    }

    private boolean valuesEqual(RuleRequest a, RuleRequest b) {
        Object left = firstValue(a);
        Object right = firstValue(b);
        if (left == null || right == null) {
            return false;
        }
        if (left instanceof String ls && right instanceof String rs) {
            return (a.caseSensitive() && b.caseSensitive()) ? ls.equals(rs) : ls.equalsIgnoreCase(rs);
        }
        if (left instanceof Number ln && right instanceof Number rn) {
            return ln.doubleValue() == rn.doubleValue();
        }
        return left.equals(right);
    }

    private Object firstValue(RuleRequest rule) {
        List<Object> values = rule.values();
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private String asString(RuleRequest rule) {
        return firstValue(rule) instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    private Comparable<Object> comparableValue(RuleRequest rule, RuleValueType type) {
        Object value = firstValue(rule);
        if (type == RuleValueType.NUMBER && value instanceof Number n) {
            return (Comparable<Object>) (Comparable<?>) Double.valueOf(n.doubleValue());
        }
        if (type == RuleValueType.SEMVER && value instanceof String s) {
            return (Comparable<Object>) (Comparable<?>) new SemVer(s);
        }
        return null;
    }

    private String describe(RuleRequest rule) {
        return (rule.negated() ? "NOT " : "") + rule.attribute() + " " + rule.operator() + " " + firstValue(rule);
    }

    /**
     * Ordering over the subset of semver this validator accepts ({@code 1.2.3} with an optional
     * pre-release/build suffix). Suffixes are ignored for ordering: they only ever make a version
     * <em>lower</em> than its release, which cannot turn a non-empty range into an empty one.
     */
    private record SemVer(String raw) implements Comparable<SemVer> {

        private int[] parts() {
            String core = raw.split("[-+]", 2)[0];
            String[] segments = core.split("\\.");
            int[] parsed = new int[4];
            for (int i = 0; i < parsed.length; i++) {
                if (i < segments.length && !segments[i].isEmpty()) {
                    try {
                        parsed[i] = Integer.parseInt(segments[i]);
                    } catch (NumberFormatException e) {
                        parsed[i] = 0;
                    }
                }
            }
            return parsed;
        }

        @Override
        public int compareTo(SemVer other) {
            int[] mine = parts();
            int[] theirs = other.parts();
            for (int i = 0; i < mine.length; i++) {
                int cmp = Integer.compare(mine[i], theirs[i]);
                if (cmp != 0) {
                    return cmp;
                }
            }
            return 0;
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
