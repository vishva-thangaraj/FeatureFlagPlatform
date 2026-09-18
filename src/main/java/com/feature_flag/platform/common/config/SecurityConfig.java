package com.feature_flag.platform.common.config;

import com.feature_flag.platform.common.security.JwtAuthenticationFilter;
import com.feature_flag.platform.common.security.config.PublicEndpoints;
import com.feature_flag.platform.common.security.filter.FilterChainExceptionHandler;
import com.feature_flag.platform.common.security.filter.ParamValidationFilter;
import com.feature_flag.platform.common.security.filter.RateLimitFilter;
import com.feature_flag.platform.common.security.filter.RoleAuthorizationFilter;
import com.feature_flag.platform.common.security.filter.TenantContextFilter;
import jakarta.servlet.Filter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.request.async.WebAsyncManagerIntegrationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class SecurityConfig {

    private final FilterChainExceptionHandler filterChainExceptionHandler;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private final TenantContextFilter tenantContextFilter;
    private final RoleAuthorizationFilter roleAuthorizationFilter;
    private final ParamValidationFilter paramValidationFilter;

    public SecurityConfig(FilterChainExceptionHandler filterChainExceptionHandler,
                          JwtAuthenticationFilter jwtAuthenticationFilter,
                          RateLimitFilter rateLimitFilter,
                          TenantContextFilter tenantContextFilter,
                          RoleAuthorizationFilter roleAuthorizationFilter,
                          ParamValidationFilter paramValidationFilter) {
        this.filterChainExceptionHandler = filterChainExceptionHandler;
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
                        // Cross-checked against api-security.json's authRequired flags at startup by
                        // PublicEndpointPolicyValidator, so the two declarations cannot drift apart.
                        .requestMatchers(PublicEndpoints.patterns()).permitAll()
                        .anyRequest().authenticated()
                )

                // ---------------------------------------------------------------------------------------
                // Chain order. Every position below is anchored to the filter immediately before it rather
                // than to a shared anchor: two filters added with the same computed order used to come out
                // in bean-declaration order, which meant the order that matters here was decided by a
                // stable sort nobody had written down.
                // ---------------------------------------------------------------------------------------

                // Outermost, so it wraps ExceptionTranslationFilter rather than pre-empting it: Spring
                // Security still translates its own authentication/access-denied exceptions, and only what
                // nothing else claimed - a RuntimeException out of one of our filters - is turned into the
                // standard ErrorResponse body instead of a container error page.
                .addFilterBefore(filterChainExceptionHandler, WebAsyncManagerIntegrationFilter.class)

                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)

                // AFTER authentication, so an authenticated caller is throttled as a caller rather than as
                // an IP address (see RateLimitFilter#resolveClientKey), and BEFORE TenantContextFilter, so
                // a throttled request is rejected before it costs a membership lookup in the database.
                //
                // The trade-off this accepts: an unauthenticated flood now pays one signature verification
                // before it is throttled. That cost is nil for the endpoints that matter - the credential
                // endpoints are in JwtAuthenticationFilter's skip list, so login/register/refresh do no JWT
                // work at all and their per-IP brute-force limits from api-security.json apply exactly as
                // before - and elsewhere it is one HMAC against a request that is still refused before any
                // database work.
                .addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class)

                // Tenant scope must be resolved before roles are checked, so RoleAuthorizationFilter
                // evaluates the target tenant's roles rather than the token's default tenant.
                .addFilterAfter(tenantContextFilter, RateLimitFilter.class)
                .addFilterAfter(roleAuthorizationFilter, TenantContextFilter.class)
                .addFilterAfter(paramValidationFilter, RoleAuthorizationFilter.class);

        return httpSecurity.build();
    }

    // -------------------------------------------------------------------------------------------------
    // Container-level registration: OFF for every security filter.
    //
    // Each of these is a @Component implementing Filter, and Spring Boot auto-registers every Filter bean
    // on the servlet container in ADDITION to the copy the security chain runs. Nothing broke only because
    // the security chain's proxy sits at order -100 while the stray registrations get LOWEST_PRECEDENCE,
    // so the in-chain copy always ran first and OncePerRequestFilter's ".FILTERED" request attribute made
    // the stray a no-op. Nothing declared that, and inverting it is catastrophic rather than merely wrong:
    // RoleAuthorizationFilter running ahead of JwtAuthenticationFilter sees an empty SecurityContext and
    // answers 403 to every request to every endpoint with a non-empty roles list.
    //
    // Disabling the registration makes the security chain the only thing that runs them.
    // SecurityFilterChainWiringTest pins it.
    // -------------------------------------------------------------------------------------------------

    @Bean
    public FilterRegistrationBean<FilterChainExceptionHandler> disableFilterChainExceptionHandlerRegistration(
            FilterChainExceptionHandler filter) {
        return chainOnly(filter);
    }

    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> disableJwtAuthenticationFilterRegistration(
            JwtAuthenticationFilter filter) {
        return chainOnly(filter);
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> disableRateLimitFilterRegistration(RateLimitFilter filter) {
        return chainOnly(filter);
    }

    @Bean
    public FilterRegistrationBean<TenantContextFilter> disableTenantContextFilterRegistration(
            TenantContextFilter filter) {
        return chainOnly(filter);
    }

    @Bean
    public FilterRegistrationBean<RoleAuthorizationFilter> disableRoleAuthorizationFilterRegistration(
            RoleAuthorizationFilter filter) {
        return chainOnly(filter);
    }

    @Bean
    public FilterRegistrationBean<ParamValidationFilter> disableParamValidationFilterRegistration(
            ParamValidationFilter filter) {
        return chainOnly(filter);
    }

    private static <T extends Filter> FilterRegistrationBean<T> chainOnly(T filter) {
        FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
