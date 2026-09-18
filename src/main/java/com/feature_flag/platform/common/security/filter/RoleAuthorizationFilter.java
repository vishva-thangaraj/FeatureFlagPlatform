package com.feature_flag.platform.common.security.filter;

import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.common.security.config.ApiSecurityConfigLoader;
import com.feature_flag.platform.common.security.config.EndpointSecurityRule;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Component
public class RoleAuthorizationFilter extends OncePerRequestFilter {

    private final ApiSecurityConfigLoader configLoader;
    private final FilterErrorWriter errorWriter;

    public RoleAuthorizationFilter(ApiSecurityConfigLoader configLoader, FilterErrorWriter errorWriter) {
        this.configLoader = configLoader;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // RequestPaths, never getServletPath(): the latter is empty outside a servlet mapping, which made
        // every rule below silently match nothing under MockMvc.
        String path = RequestPaths.of(request);
        String method = request.getMethod();

        Optional<EndpointSecurityRule> ruleOpt = configLoader.getRule(path, method);
        if (ruleOpt.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        EndpointSecurityRule rule = ruleOpt.get();
        List<String> requiredRoles = rule.getRoles();

        if (requiredRoles == null || requiredRoles.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof TenantUserDetails userDetails)) {
            if (rule.isAuthRequired()) {
                errorWriter.write(response, HttpStatus.FORBIDDEN, "Insufficient permissions.");
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        boolean hasRole = userDetails.getRoles().stream()
                .anyMatch(requiredRoles::contains);

        if (!hasRole) {
            errorWriter.write(response, HttpStatus.FORBIDDEN,
                    "You do not have the required role to access this resource.");
            return;
        }

        filterChain.doFilter(request, response);
    }
}