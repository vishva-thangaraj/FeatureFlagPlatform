package com.feature_flag.platform.common.security.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class ApiSecurityConfig {

    private DefaultsConfig defaults = new DefaultsConfig();
    private List<EndpointSecurityRule> endpoints = List.of();

    @Getter
    @Setter
    @NoArgsConstructor
    public static class DefaultsConfig {
        private boolean authRequired = true;
        private ThrottleConfig throttle = new ThrottleConfig();
    }
}