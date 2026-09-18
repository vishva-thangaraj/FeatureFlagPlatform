package com.feature_flag.platform.common.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Loads the declarative API security policy ({@code api-security.json}) that drives role enforcement,
 * throttling and parameter allow-listing.
 *
 * <p>This policy is the ONLY thing standing between an authenticated VIEWER and the OWNER/ADMIN-only
 * endpoints, so it is loaded <strong>fail-closed</strong>: if the file is missing, unreadable, malformed
 * or internally invalid, the bean fails to initialise and the application refuses to start. Previously a
 * single typo in the JSON was swallowed as a WARN and left every rule map empty, which made
 * {@link #getRule(String, String)} return {@link Optional#empty()} for every request and silently disabled
 * role checks platform-wide.
 */
@Component
public class ApiSecurityConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ApiSecurityConfigLoader.class);

    /** HTTP methods a rule may declare. A rule with a missing/unknown method used to NPE inside the filter chain. */
    private static final Set<String> VALID_METHODS =
            Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE");

    private final ObjectMapper objectMapper;
    private final String resourcePath;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final Map<String, EndpointSecurityRule> exactRuleMap = new ConcurrentHashMap<>();
    private final List<EndpointSecurityRule> patternRules = new CopyOnWriteArrayList<>();
    private ApiSecurityConfig config;

    public ApiSecurityConfigLoader(ObjectMapper objectMapper,
                                   @Value("${app.security.api-policy-resource:api-security.json}")
                                   String resourcePath) {
        this.objectMapper = objectMapper;
        this.resourcePath = resourcePath;
    }

    @PostConstruct
    public void load() {
        ApiSecurityConfig loaded;
        try (InputStream is = new ClassPathResource(resourcePath).getInputStream()) {
            loaded = objectMapper.readValue(is, ApiSecurityConfig.class);
        } catch (IOException e) {
            // Fail CLOSED: a security policy that cannot be read must stop the application, never
            // degrade into "no rules == no enforcement".
            throw new IllegalStateException(
                    "Refusing to start: the API security policy '" + resourcePath
                            + "' could not be loaded, which would disable role enforcement for every endpoint: "
                            + e.getMessage(), e);
        }

        // Build the indexes off to the side so a validation failure can never leave the live maps
        // half-populated (which would enforce some rules and silently drop the rest).
        Map<String, EndpointSecurityRule> exact = new LinkedHashMap<>();
        List<EndpointSecurityRule> patterns = new ArrayList<>();
        validateAndIndex(loaded, exact, patterns);

        exactRuleMap.clear();
        exactRuleMap.putAll(exact);
        patternRules.clear();
        patternRules.addAll(patterns);
        this.config = loaded;

        log.info("Loaded {} API security rules from {} ({} exact, {} pattern)",
                exact.size() + patterns.size(), resourcePath, exact.size(), patterns.size());
    }

    private void validateAndIndex(ApiSecurityConfig loaded,
                                  Map<String, EndpointSecurityRule> exact,
                                  List<EndpointSecurityRule> patterns) {
        if (loaded == null) {
            throw invalid("the policy document is empty");
        }
        ApiSecurityConfig.DefaultsConfig defaults = loaded.getDefaults();
        if (defaults == null) {
            throw invalid("'defaults' is missing");
        }
        validateThrottle(defaults.getThrottle(), "defaults");

        List<EndpointSecurityRule> endpoints = loaded.getEndpoints();
        if (endpoints == null || endpoints.isEmpty()) {
            // An empty rule list is indistinguishable from "enforcement disabled"; treat it as a mistake.
            throw invalid("'endpoints' declares no rules, which would leave every endpoint unguarded");
        }

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < endpoints.size(); i++) {
            EndpointSecurityRule rule = endpoints.get(i);
            String where = "endpoints[" + i + "]";
            if (rule == null) {
                throw invalid(where + " is null");
            }
            if (rule.getPath() == null || rule.getPath().isBlank()) {
                throw invalid(where + " has no 'path'");
            }
            where = where + " (" + rule.getPath() + ")";
            if (rule.getMethod() == null || rule.getMethod().isBlank()) {
                throw invalid(where + " has no 'method'");
            }
            String method = rule.getMethod().trim().toUpperCase();
            if (!VALID_METHODS.contains(method)) {
                throw invalid(where + " has unknown HTTP method '" + rule.getMethod() + "'");
            }
            if (rule.getRoles() == null) {
                throw invalid(where + " has 'roles': null; omit the field or use [] to require no role");
            }
            if (rule.getAllowedParams() == null) {
                throw invalid(where + " has 'allowedParams': null; omit the field or use []");
            }
            if (rule.getThrottle() != null) {
                validateThrottle(rule.getThrottle(), where);
            }
            if (!seen.add(buildKey(rule.getPath(), method))) {
                throw invalid("duplicate rule for " + method + " " + rule.getPath()
                        + "; the later rule would never be applied");
            }

            if (pathMatcher.isPattern(rule.getPath())) {
                patterns.add(rule);
            } else {
                exact.put(buildKey(rule.getPath(), method), rule);
            }
        }
    }

    private void validateThrottle(ThrottleConfig throttle, String where) {
        if (throttle == null) {
            throw invalid(where + " has no 'throttle'");
        }
        if (throttle.getRequestsPerMinute() <= 0) {
            throw invalid(where + " has throttle.requestsPerMinute=" + throttle.getRequestsPerMinute()
                    + "; it must be greater than 0");
        }
        if (throttle.getBurstSize() <= 0) {
            throw invalid(where + " has throttle.burstSize=" + throttle.getBurstSize()
                    + "; it must be greater than 0");
        }
    }

    private IllegalStateException invalid(String detail) {
        return new IllegalStateException(
                "Refusing to start: invalid API security policy '" + resourcePath + "': " + detail);
    }

    /**
     * Resolves the rule guarding a request. The returned rule's {@link EndpointSecurityRule#getPath()} is the
     * rule PATTERN (e.g. {@code /api/v1/features/*}), which callers should prefer over the concrete request
     * path whenever they key state per endpoint.
     */
    public Optional<EndpointSecurityRule> getRule(String path, String method) {
        if (path == null || method == null) {
            return Optional.empty();
        }
        EndpointSecurityRule exact = exactRuleMap.get(buildKey(path, method));
        if (exact != null) {
            return Optional.of(exact);
        }
        return patternRules.stream()
                // getMethod() is guaranteed non-null: rules missing a method are rejected at startup.
                .filter(rule -> rule.getMethod().equalsIgnoreCase(method)
                        && pathMatcher.match(rule.getPath(), path))
                .findFirst();
    }

    public ThrottleConfig getDefaultThrottle() {
        return config.getDefaults().getThrottle();
    }

    /**
     * Whether an endpoint with no rule of its own requires authentication. Read by
     * {@link PublicEndpointPolicyValidator}, which cross-checks this policy against the {@code permitAll}
     * list in {@code SecurityConfig}.
     */
    public boolean isDefaultAuthRequired() {
        return config.getDefaults().isAuthRequired();
    }

    /** Every rule in the policy, in declaration order. */
    public List<EndpointSecurityRule> getRules() {
        return List.copyOf(config.getEndpoints());
    }

    private String buildKey(String path, String method) {
        return method.toUpperCase() + ":" + path;
    }
}
