package com.feature_flag.platform.common.security.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Keeps {@code SecurityConfig}'s {@code permitAll} list and {@code api-security.json}'s {@code authRequired}
 * flags from drifting apart, by refusing to start when they disagree.
 *
 * <p>Who may reach an endpoint unauthenticated was declared in two independent places. They happen to agree
 * today, but nothing said so: adding a pattern to {@code permitAll} without the matching
 * {@code "authRequired": false} rule opened the endpoint to anonymous callers while the policy file still
 * claimed it was protected - and {@code RoleAuthorizationFilter}, which consults {@code isAuthRequired()} to
 * decide whether a principal-less request is a 403 or a pass-through, would have been reasoning from the
 * stale half. That is a fail-open change with no symptom until someone exploits it.
 *
 * <p>Collapsing to one source of truth would mean deriving the {@code permitAll} matchers from the JSON,
 * which puts a file on the classpath in charge of Spring Security's own matcher list; keeping both and
 * cross-checking them at startup gets the same guarantee without that coupling. The check compares pattern
 * strings literally - both files use the same Ant syntax - so an equivalent-but-differently-spelled pattern
 * is reported too, deliberately.
 */
@Component
public class PublicEndpointPolicyValidator {

    private static final Logger log = LoggerFactory.getLogger(PublicEndpointPolicyValidator.class);

    private final ApiSecurityConfigLoader configLoader;

    public PublicEndpointPolicyValidator(ApiSecurityConfigLoader configLoader) {
        this.configLoader = configLoader;
    }

    @PostConstruct
    public void validate() {
        List<String> problems = check(PublicEndpoints.PATTERNS, configLoader.getRules(),
                configLoader.isDefaultAuthRequired());
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start: SecurityConfig's permitAll list and api-security.json disagree about"
                            + " which endpoints are public:\n  - " + String.join("\n  - ", problems));
        }
        log.info("Public endpoint policy is consistent: {} permitAll pattern(s) match their"
                + " api-security.json rules", PublicEndpoints.PATTERNS.size());
    }

    // Visible for testing: the whole check as a pure function over the two declarations.
    static List<String> check(List<String> permitAllPatterns,
                              List<EndpointSecurityRule> rules,
                              boolean defaultAuthRequired) {
        List<String> problems = new ArrayList<>();

        if (!defaultAuthRequired) {
            // SecurityConfig ends with .anyRequest().authenticated(); a policy default of "public" would
            // mean the two disagree about every endpoint that has no rule of its own.
            problems.add("api-security.json sets defaults.authRequired=false, but SecurityConfig requires"
                    + " authentication for anyRequest(); an endpoint with no rule would be public in one"
                    + " and protected in the other");
        }

        Set<String> permitAll = new LinkedHashSet<>(permitAllPatterns);

        // permitAll -> policy: an endpoint Spring lets through anonymously must be declared public.
        for (String pattern : permitAll) {
            List<EndpointSecurityRule> matching = rules.stream()
                    .filter(rule -> pattern.equals(rule.getPath()))
                    .toList();
            if (matching.isEmpty()) {
                problems.add("'" + pattern + "' is permitAll in SecurityConfig but has no rule in"
                        + " api-security.json");
                continue;
            }
            String stillProtected = matching.stream()
                    .filter(EndpointSecurityRule::isAuthRequired)
                    .map(EndpointSecurityRule::getMethod)
                    .collect(Collectors.joining(", "));
            if (!stillProtected.isEmpty()) {
                problems.add("'" + pattern + "' is permitAll in SecurityConfig but api-security.json still"
                        + " declares authRequired=true for: " + stillProtected);
            }
        }

        // policy -> permitAll: an endpoint the policy calls public must actually be reachable.
        for (EndpointSecurityRule rule : rules) {
            if (!rule.isAuthRequired() && !permitAll.contains(rule.getPath())) {
                problems.add("api-security.json declares " + rule.getMethod() + " '" + rule.getPath()
                        + "' as authRequired=false, but it is not in SecurityConfig's permitAll list");
            }
        }

        return problems;
    }
}
