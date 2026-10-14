package com.feature_flag.platform.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Serves a machine-readable description of the REST surface at {@code /v3/api-docs}, with Swagger UI at
 * {@code /swagger-ui.html}.
 *
 * <p>It exists for the dashboard: the client generates its TypeScript types from that document, so a
 * controller or DTO change that breaks the contract becomes a compile error in the client's merge request
 * instead of a bug report. API_REFERENCE.md stays as the narrative explanation of <em>why</em> the API
 * behaves as it does; what is generated here is the shape.
 *
 * <p><strong>Not for production.</strong> The document maps every endpoint, parameter and payload, which is
 * a free reconnaissance pass for anyone who asks. Two independent things keep it off: this configuration is
 * confined to the {@code dev} and {@code docker} profiles, so the permit rule below does not exist
 * elsewhere, and {@code springdoc.api-docs.enabled} is {@code false} in application.properties, which every
 * other profile inherits. Either alone would do; both means enabling it in production takes a deliberate
 * change in two files rather than one forgotten override.
 */
@Configuration
@Profile({"dev", "docker"})
public class OpenApiConfig {

    private static final String[] DOC_PATHS = {
            "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml",
            "/swagger-ui", "/swagger-ui/**", "/swagger-ui.html"
    };

    /**
     * A chain of its own, ahead of {@code SecurityConfig}'s.
     *
     * <p>The alternative — adding these paths to {@code PublicEndpoints} — would have been wrong twice:
     * {@code PublicEndpointPolicyValidator} requires every entry there to appear in {@code api-security.json}
     * with {@code authRequired: false}, and that file describes the API itself, not the scaffolding around
     * it. Keeping the documentation on a separate chain also keeps the JWT, rate-limit, tenant and role
     * filters off these requests, none of which have anything to say about a static JSON document.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain openApiSecurityFilterChain(HttpSecurity httpSecurity) throws Exception {
        return httpSecurity
                .securityMatcher(DOC_PATHS)
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    @Bean
    public OpenAPI featureFlagOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Feature Flag Platform API")
                        .version("v1")
                        .description("""
                                Dashboard and SDK API.

                                Two credentials exist. The dashboard sends a bearer JWT and names the tenant \
                                it is acting on with the X-Tenant-Id header. The SDK surface under \
                                /api/v1/sdk/ takes an environment-scoped API key instead, and takes its \
                                tenant and environment from the key rather than from a header.

                                Ids are TSIDs. Responses send them as JSON strings because they exceed the \
                                range a JavaScript Number represents exactly; request bodies accept either a \
                                quoted or an unquoted number."""))
                .components(new Components()
                        .addSecuritySchemes("bearer", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Dashboard access token from /api/v1/auth/login."))
                        .addSecuritySchemes("apiKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-API-Key")
                                .description("Environment-scoped SDK key. Only the /api/v1/sdk/ paths take it.")));
    }
}
