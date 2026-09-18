package com.feature_flag.platform.common.security.filter;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The one way the security filters derive the path they match their rules against.
 *
 * <p>Deliberately not {@link HttpServletRequest#getServletPath()}. That returns the full path only because
 * the dispatcher servlet happens to be mapped at {@code /}, and it is <em>empty</em> whenever the request
 * was not routed through a servlet mapping — notably under MockMvc, where {@link
 * HttpServletRequest#getRequestURI()} carries the path instead.
 *
 * <p>The practical consequence was a testing-integrity hole rather than a live vulnerability: production
 * behaviour was correct, but every integration test matched its rules against {@code ""}, so the path rules
 * in {@code RoleAuthorizationFilter}, {@code RateLimitFilter} and {@code ParamValidationFilter} never
 * actually ran under test. They looked covered while being dead. Deriving the path from the request URI
 * gives the same answer in production and keeps the tests honest.
 *
 * <p>{@code getServletPath()} remains the fallback for the (theoretical) case of a request that carries no
 * URI at all.
 */
public final class RequestPaths {

    private RequestPaths() {
    }

    /** The request path with the context path stripped, e.g. {@code /api/v1/tenants/7}. */
    public static String of(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || uri.isEmpty()) {
            String servletPath = request.getServletPath();
            return servletPath == null ? "" : servletPath;
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return uri;
    }
}
