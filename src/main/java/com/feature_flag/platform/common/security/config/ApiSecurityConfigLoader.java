package com.feature_flag.platform.common.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class ApiSecurityConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ApiSecurityConfigLoader.class);

    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final Map<String, EndpointSecurityRule> exactRuleMap = new ConcurrentHashMap<>();
    private final List<EndpointSecurityRule> patternRules = new CopyOnWriteArrayList<>();
    private ApiSecurityConfig config;

    public ApiSecurityConfigLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void load() {
        try (InputStream is = new ClassPathResource("api-security.json").getInputStream()) {
            config = objectMapper.readValue(is, ApiSecurityConfig.class);
            for (EndpointSecurityRule rule : config.getEndpoints()) {
                if (pathMatcher.isPattern(rule.getPath())) {
                    patternRules.add(rule);
                } else {
                    String key = buildKey(rule.getPath(), rule.getMethod());
                    exactRuleMap.put(key, rule);
                }
            }
            log.info("Loaded {} API security rules ({} exact, {} pattern)",
                    exactRuleMap.size() + patternRules.size(), exactRuleMap.size(), patternRules.size());
        } catch (IOException e) {
            log.warn("Could not load api-security.json, using defaults: {}", e.getMessage());
            config = new ApiSecurityConfig();
        }
    }

    public Optional<EndpointSecurityRule> getRule(String path, String method) {
        EndpointSecurityRule exact = exactRuleMap.get(buildKey(path, method));
        if (exact != null) {
            return Optional.of(exact);
        }
        return patternRules.stream()
                .filter(rule -> rule.getMethod().equalsIgnoreCase(method)
                        && pathMatcher.match(rule.getPath(), path))
                .findFirst();
    }

    public ThrottleConfig getDefaultThrottle() {
        return config.getDefaults().getThrottle();
    }

    public boolean isDefaultAuthRequired() {
        return config.getDefaults().isAuthRequired();
    }

    private String buildKey(String path, String method) {
        return method.toUpperCase() + ":" + path;
    }
}