package com.feature_flag.platform.common.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The api-security.json policy is the only thing enforcing OWNER/ADMIN on write endpoints, so it must load
 * fail-CLOSED: anything that would leave the rule maps empty (missing file, JSON typo, invalid rule) has to
 * stop the application instead of degrading into "no rule matched == no role required".
 */
class ApiSecurityConfigLoaderTest {

    private ApiSecurityConfigLoader loaderFor(String resource) {
        return new ApiSecurityConfigLoader(new ObjectMapper(), resource);
    }

    private String messageOf(String resource) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> loaderFor(resource).load());
        return e.getMessage();
    }

    @Test
    void malformedPolicy_failsStartupInsteadOfSilentlyDisablingRoleChecks() {
        String message = messageOf("policy/malformed.json");

        assertTrue(message.contains("Refusing to start"), message);
        assertTrue(message.contains("policy/malformed.json"), message);
    }

    @Test
    void malformedPolicy_leavesNoUsableRules_whichIsWhyItMustNotBeSwallowed() {
        ApiSecurityConfigLoader loader = loaderFor("policy/malformed.json");

        assertThrows(IllegalStateException.class, loader::load);

        // Documents the vulnerability: if the exception were swallowed, this empty result is what every
        // request would see, and RoleAuthorizationFilter waves through anything without a matching rule.
        assertEquals(Optional.empty(), loader.getRule("/api/v1/features/1", "DELETE"));
    }

    @Test
    void missingPolicyFile_failsStartup() {
        String message = messageOf("policy/does-not-exist.json");

        assertTrue(message.contains("Refusing to start"), message);
    }

    @Test
    void ruleMissingMethod_isRejectedAtLoad() {
        // A method-less rule used to load fine and then NPE inside getRule() for every request that reached
        // the pattern scan, turning the whole API into 500s.
        String message = messageOf("policy/missing-method.json");

        assertTrue(message.contains("no 'method'"), message);
        assertTrue(message.contains("/api/v1/features/*"), message);
    }

    @Test
    void ruleWithUnknownHttpMethod_isRejectedAtLoad() {
        String message = messageOf("policy/unknown-method.json");

        assertTrue(message.contains("unknown HTTP method"), message);
    }

    @Test
    void ruleWithNullRoles_isRejectedAtLoad() {
        String message = messageOf("policy/null-roles.json");

        assertTrue(message.contains("'roles': null"), message);
    }

    @Test
    void policyWithNoRules_isRejectedAtLoad() {
        String message = messageOf("policy/empty-endpoints.json");

        assertTrue(message.contains("no rules"), message);
    }

    @Test
    void ruleWithNonPositiveThrottle_isRejectedAtLoad() {
        String message = messageOf("policy/zero-throttle.json");

        assertTrue(message.contains("requestsPerMinute"), message);
    }

    @Test
    void duplicateRule_isRejectedAtLoad() {
        String message = messageOf("policy/duplicate-rule.json");

        assertTrue(message.contains("duplicate rule"), message);
    }

    @Test
    void realPolicy_loadsAndResolvesRulesByPattern() {
        ApiSecurityConfigLoader loader = loaderFor("api-security.json");
        loader.load();

        EndpointSecurityRule rule = loader.getRule("/api/v1/features/42", "DELETE").orElseThrow();

        assertEquals("/api/v1/features/*", rule.getPath(),
                "the rule exposes the PATTERN, which is what per-endpoint state must be keyed by");
        assertTrue(rule.getRoles().contains("OWNER"));
        assertTrue(loader.isDefaultAuthRequired());
        assertEquals(60, loader.getDefaultThrottle().getRequestsPerMinute());
    }

    @Test
    void realPolicy_exactRulesWinAndUnknownPathsHaveNoRule() {
        ApiSecurityConfigLoader loader = loaderFor("api-security.json");
        loader.load();

        assertTrue(loader.getRule("/api/v1/auth/login", "POST").isPresent());
        assertFalse(loader.getRule("/api/v1/auth/login", "GET").isPresent());
        assertFalse(loader.getRule("/actuator/health", "GET").isPresent());
        assertFalse(loader.getRule(null, "GET").isPresent());
    }
}
