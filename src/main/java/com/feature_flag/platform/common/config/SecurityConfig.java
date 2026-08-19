package com.feature_flag.platform.common.config;

import com.feature_flag.platform.common.security.JwtAuthenticationFilter;
import com.feature_flag.platform.common.security.filter.ParamValidationFilter;
import com.feature_flag.platform.common.security.filter.RateLimitFilter;
import com.feature_flag.platform.common.security.filter.RoleAuthorizationFilter;
import com.feature_flag.platform.common.security.filter.TenantContextFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private final TenantContextFilter tenantContextFilter;
    private final RoleAuthorizationFilter roleAuthorizationFilter;
    private final ParamValidationFilter paramValidationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          RateLimitFilter rateLimitFilter,
                          TenantContextFilter tenantContextFilter,
                          RoleAuthorizationFilter roleAuthorizationFilter,
                          ParamValidationFilter paramValidationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.tenantContextFilter = tenantContextFilter;
        this.roleAuthorizationFilter = roleAuthorizationFilter;
        this.paramValidationFilter = paramValidationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity) throws Exception {
        httpSecurity
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout",
                                "/api/v1/invitations/*/accept",
                                "/api/v1/invitations/*/decline",
                                "/api/v1/invitations/*"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // Tenant scope must be resolved before roles are checked, so RoleAuthorizationFilter
                // evaluates the target tenant's roles rather than the token's default tenant.
                .addFilterAfter(tenantContextFilter, JwtAuthenticationFilter.class)
                .addFilterAfter(roleAuthorizationFilter, TenantContextFilter.class)
                .addFilterAfter(paramValidationFilter, RoleAuthorizationFilter.class);

        return httpSecurity.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}