package com.feature_flag.platform.common.security.filter;

import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.common.security.config.ApiSecurityConfigLoader;
import com.feature_flag.platform.common.security.config.EndpointSecurityRule;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

    public RoleAuthorizationFilter(ApiSecurityConfigLoader configLoader) {
        this.configLoader = configLoader;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getServletPath();
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
                response.setStatus(HttpStatus.FORBIDDEN.value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write(
                        "{\"status\":403,\"error\":\"Forbidden\",\"message\":\"Insufficient permissions.\"}");
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        boolean hasRole = userDetails.getRoles().stream()
                .anyMatch(requiredRoles::contains);

        if (!hasRole) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                    "{\"status\":403,\"error\":\"Forbidden\",\"message\":\"You do not have the required role to access this resource.\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }
}