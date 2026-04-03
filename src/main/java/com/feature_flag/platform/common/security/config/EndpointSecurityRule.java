package com.feature_flag.platform.common.security.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class EndpointSecurityRule {

    private String path;
    private String method;
    private boolean authRequired = true;
    private List<String> roles = List.of();
    private ThrottleConfig throttle;
    private List<String> allowedParams = List.of();
}