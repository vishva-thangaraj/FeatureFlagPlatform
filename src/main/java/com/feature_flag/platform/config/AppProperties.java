package com.feature_flag.platform.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class AppProperties {

    private String baseUrl;
    private String clientBaseUrl;

    private Mail mail = new Mail();

    @Getter
    @Setter
    public static class Mail {
        private String from;
    }
}
