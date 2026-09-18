package com.feature_flag.platform.common.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who may reach an endpoint without credentials was declared twice - once as {@code permitAll} in
 * {@code SecurityConfig}, once as {@code authRequired} in {@code api-security.json} - with nothing checking
 * that the two agreed. Adding a {@code permitAll} entry without its matching rule opens an endpoint to
 * anonymous callers while the policy file still claims it is protected, and
 * {@code RoleAuthorizationFilter}, which reads {@code isAuthRequired()} to decide whether a principal-less
 * request is a 403 or a pass-through, would then be reasoning from the stale half. Fail-open, with no
 * symptom until someone finds it.
 */
class PublicEndpointPolicyValidatorTest {

    private static List<EndpointSecurityRule> realRules() {
        ApiSecurityConfigLoader loader = new ApiSecurityConfigLoader(new ObjectMapper(), "api-security.json");
        loader.load();
        return loader.getRules();
    }

    private static EndpointSecurityRule rule(String path, String method, boolean authRequired) {
        EndpointSecurityRule rule = new EndpointSecurityRule();
        rule.setPath(path);
        rule.setMethod(method);
        rule.setAuthRequired(authRequired);
        return rule;
    }

    /** The state of the world as shipped: the check has to be satisfiable, not just strict. */
    @Test
    void theShippedPermitAllListAndPolicyAgree() {
        assertThat(PublicEndpointPolicyValidator.check(PublicEndpoints.PATTERNS, realRules(), true))
                .isEmpty();
    }

    /** The startup wiring itself, against the real policy file. */
    @Test
    void theValidatorBeanAcceptsTheShippedConfiguration() {
        ApiSecurityConfigLoader loader = new ApiSecurityConfigLoader(new ObjectMapper(), "api-security.json");
        loader.load();

        new PublicEndpointPolicyValidator(loader).validate();
    }

    // ---------------------------------------------------------------- the drift it must catch

    /** The dangerous direction: an endpoint let through anonymously that the policy still guards. */
    @Test
    void aPermitAllPatternWithNoRuleAtAllIsReported() {
        List<String> permitAll = new ArrayList<>(PublicEndpoints.PATTERNS);
        permitAll.add("/api/v1/features/*/danger");

        assertThat(PublicEndpointPolicyValidator.check(permitAll, realRules(), true))
                .anySatisfy(problem -> assertThat(problem)
                        .contains("/api/v1/features/*/danger")
                        .contains("no rule in api-security.json"));
    }

    @Test
    void aPermitAllPatternThePolicyStillProtectsIsReported() {
        List<String> permitAll = new ArrayList<>(PublicEndpoints.PATTERNS);
        permitAll.add("/api/v1/features");

        assertThat(PublicEndpointPolicyValidator.check(permitAll, realRules(), true))
                .anySatisfy(problem -> assertThat(problem)
                        .contains("/api/v1/features")
                        .contains("authRequired=true"));
    }

    /** The other direction: the policy calls it public but Spring Security will still demand a token. */
    @Test
    void aPolicyRuleMarkedPublicButNotPermitAllIsReported() {
        List<EndpointSecurityRule> rules = new ArrayList<>(realRules());
        rules.add(rule("/api/v1/health", "GET", false));

        assertThat(PublicEndpointPolicyValidator.check(PublicEndpoints.PATTERNS, rules, true))
                .anySatisfy(problem -> assertThat(problem)
                        .contains("/api/v1/health")
                        .contains("not in SecurityConfig's permitAll list"));
    }

    /**
     * {@code defaults.authRequired} is the policy's answer for every endpoint with no rule of its own;
     * SecurityConfig's answer is {@code .anyRequest().authenticated()}. Flipping it would make the two
     * disagree about every such endpoint at once. Nothing read this flag before.
     */
    @Test
    void aPolicyDefaultOfPublicIsReported() {
        assertThat(PublicEndpointPolicyValidator.check(PublicEndpoints.PATTERNS, realRules(), false))
                .anySatisfy(problem -> assertThat(problem).contains("defaults.authRequired=false"));
    }

    @Test
    void startupFailsWhenTheTwoDeclarationsDisagree() {
        ApiSecurityConfigLoader loader = new ApiSecurityConfigLoader(new ObjectMapper(), "api-security.json") {
            @Override
            public boolean isDefaultAuthRequired() {
                return false;
            }
        };
        loader.load();

        assertThatThrownBy(() -> new PublicEndpointPolicyValidator(loader).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start");
    }
}
