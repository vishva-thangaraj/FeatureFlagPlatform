package com.feature_flag.platform.common.security.filter;

import com.feature_flag.platform.common.security.config.ApiSecurityConfigLoader;
import com.feature_flag.platform.common.security.config.EndpointSecurityRule;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class ParamValidationFilter extends OncePerRequestFilter {

    private final ApiSecurityConfigLoader configLoader;
    private final FilterErrorWriter errorWriter;

    public ParamValidationFilter(ApiSecurityConfigLoader configLoader, FilterErrorWriter errorWriter) {
        this.configLoader = configLoader;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // See RequestPaths: getServletPath() is empty outside a servlet mapping.
        String path = RequestPaths.of(request);
        String method = request.getMethod();

        Optional<EndpointSecurityRule> ruleOpt = configLoader.getRule(path, method);
        if (ruleOpt.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        EndpointSecurityRule rule = ruleOpt.get();
        List<String> allowedParams = rule.getAllowedParams();

        if (allowedParams == null || allowedParams.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        // Validate query parameters for GET requests
        if ("GET".equalsIgnoreCase(method)) {
            Set<String> queryParams = request.getParameterMap().keySet();
            Set<String> allowedSet = Set.copyOf(allowedParams);

            for (String param : queryParams) {
                if (!allowedSet.contains(param)) {
                    errorWriter.write(response, HttpStatus.BAD_REQUEST, "Disallowed parameter: " + param);
                    return;
                }
            }
        }

        filterChain.doFilter(request, response);
    }
}