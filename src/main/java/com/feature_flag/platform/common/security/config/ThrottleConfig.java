package com.feature_flag.platform.common.security.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ThrottleConfig {

    private int requestsPerMinute = 60;
    private int burstSize = 10;
}