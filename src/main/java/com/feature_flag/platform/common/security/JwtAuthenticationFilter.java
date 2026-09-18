package com.feature_flag.platform.common.security;

import com.feature_flag.platform.common.security.filter.RequestPaths;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.RequiredTypeException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtTokenProvider jwtTokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // RequestPaths, never getServletPath(): the latter is empty outside a servlet mapping, so under
        // MockMvc this skip-list matched nothing and the filter ran on the credential endpoints too.
        String path = RequestPaths.of(request);
        // Logout is deliberately NOT here. It used to be, which meant no principal was ever established
        // for it, so TokenServiceImpl.logout could not tell who was calling and revoked whichever refresh
        // token it was handed — anyone holding a leaked token could end someone else's session. The
        // ownership check there only bites if the caller is authenticated first.
        return path.equals("/api/v1/auth/register")
                || path.equals("/api/v1/auth/login")
                || path.equals("/api/v1/auth/refresh");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);

            if (jwtTokenProvider.validateAccessToken(token)) {
                Claims claims = jwtTokenProvider.parseAccessToken(token);

                // A well-signed token whose claims are the wrong SHAPE is still an unusable token, not a
                // server fault. Long.parseLong / the typed claim getters used to throw straight out of the
                // filter chain, past ExceptionTranslationFilter and past @RestControllerAdvice, and the
                // container answered with its own HTML error page instead of an ErrorResponse. Treat it as
                // "no principal" and let the authorization rules answer 401/403 in the normal shape.
                TenantUserDetails userDetails = toPrincipal(claims);

                if (userDetails != null) {
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    /** Builds the principal from the token's claims, or null when the claims are not usable. */
    private TenantUserDetails toPrincipal(Claims claims) {
        try {
            String subject = claims.getSubject();
            if (subject == null || subject.isBlank()) {
                return null;
            }
            Long userId = Long.parseLong(subject.trim());
            String email = claims.get("email", String.class);
            Long tenantId = claims.get("tenantId", Long.class);

            @SuppressWarnings("unchecked")
            List<String> roles = claims.get("roles", List.class);
            if (roles == null) {
                roles = Collections.emptyList();
            }

            return new TenantUserDetails(userId, email, tenantId, roles);
        } catch (NumberFormatException | RequiredTypeException e) {
            log.debug("Rejecting a token whose claims are malformed: {}", e.getMessage());
            return null;
        }
    }
}