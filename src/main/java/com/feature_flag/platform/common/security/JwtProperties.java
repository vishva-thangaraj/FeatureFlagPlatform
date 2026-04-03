package com.feature_flag.platform.common.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "jwt")
@Getter
@Setter
public class JwtProperties {

    private String secret;

    private long accessTokenExpirationMs = 900_000; // 15 minutes

    private long refreshTokenExpirationMs = 604_800_000; // 7 days

    private long invitationExpirationMs = 604_800_000; // 7 days
}