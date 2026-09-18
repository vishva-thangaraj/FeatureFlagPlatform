package com.feature_flag.platform.common.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * The outermost link of the security chain: turns anything that escapes a filter into the API's standard
 * {@code ErrorResponse} body.
 *
 * <p>The five security filters all sit well ahead of Spring Security's {@code ExceptionTranslationFilter},
 * and a filter is ahead of the {@code DispatcherServlet} by definition, so it is ahead of
 * {@code @RestControllerAdvice} too. A {@code RuntimeException} thrown inside one of them — a malformed
 * {@code sub} claim in {@code JwtAuthenticationFilter}, a {@code DataAccessException} from
 * {@code TenantMembershipResolver} when the database blips — therefore reached neither handler and
 * surfaced as the servlet container's own HTML error page: a different content type, a different shape,
 * and a stack trace where the client expected {@code {status, error, message, timestamp}}.
 *
 * <p>This filter is registered <em>before</em> everything else in the chain, so it wraps
 * {@code ExceptionTranslationFilter} rather than pre-empting it: authentication and access-denied
 * exceptions are still translated by Spring Security exactly as before, and only what nothing else claimed
 * lands here.
 *
 * <p>The message is deliberately generic. Whatever went wrong is logged at ERROR with the stack trace;
 * none of it is echoed to the caller.
 */
@Component
public class FilterChainExceptionHandler extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FilterChainExceptionHandler.class);

    private static final String GENERIC_MESSAGE = "An unexpected error occurred.";

    private final FilterErrorWriter errorWriter;

    public FilterChainExceptionHandler(FilterErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } catch (RuntimeException e) {
            // Deliberately only RuntimeException: an IOException this far out is a connection that has
            // already gone away, and writing a body into it would only trade one failure for another.
            log.error("Unhandled error in the filter chain for {} {}",
                    request.getMethod(), RequestPaths.of(request), e);
            if (response.isCommitted()) {
                // The body is already on the wire; there is nothing left to rewrite, and swallowing the
                // failure here would hide a half-written response. Let the container deal with it.
                throw e;
            }
            response.reset();
            errorWriter.write(response, HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_MESSAGE);
        }
    }
}
