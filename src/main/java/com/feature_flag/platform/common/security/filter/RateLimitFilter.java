package com.feature_flag.platform.common.security.filter;

import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.common.security.config.ApiSecurityConfigLoader;
import com.feature_flag.platform.common.security.config.EndpointSecurityRule;
import com.feature_flag.platform.common.security.config.ThrottleConfig;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final ApiSecurityConfigLoader configLoader;
    private final FilterErrorWriter errorWriter;
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(ApiSecurityConfigLoader configLoader, FilterErrorWriter errorWriter) {
        this.configLoader = configLoader;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getServletPath();
        String method = request.getMethod();

        ThrottleConfig throttle = configLoader.getRule(path, method)
                .map(EndpointSecurityRule::getThrottle)
                .orElse(configLoader.getDefaultThrottle());

        String clientKey = resolveClientKey(request);
        String bucketKey = method + ":" + path + ":" + clientKey;

        TokenBucket bucket = buckets.computeIfAbsent(bucketKey,
                k -> new TokenBucket(throttle.getRequestsPerMinute(), throttle.getBurstSize()));

        if (!bucket.tryConsume()) {
            errorWriter.write(response, HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded. Try again later.");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String resolveClientKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof TenantUserDetails userDetails) {
            return "user:" + userDetails.getUserId();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return "ip:" + forwarded.split(",")[0].trim();
        }
        return "ip:" + request.getRemoteAddr();
    }

    private static class TokenBucket {
        private final int maxTokens;
        private final double refillRatePerMs;
        private final AtomicInteger tokens;
        private final AtomicLong lastRefillTime;

        TokenBucket(int requestsPerMinute, int burstSize) {
            this.maxTokens = burstSize;
            this.refillRatePerMs = requestsPerMinute / 60000.0;
            this.tokens = new AtomicInteger(burstSize);
            this.lastRefillTime = new AtomicLong(System.currentTimeMillis());
        }

        boolean tryConsume() {
            refill();
            return tokens.getAndUpdate(t -> t > 0 ? t - 1 : 0) > 0;
        }

        private void refill() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefillTime.get();
            int newTokens = (int) (elapsed * refillRatePerMs);
            if (newTokens > 0) {
                lastRefillTime.set(now);
                tokens.updateAndGet(t -> Math.min(maxTokens, t + newTokens));
            }
        }
    }
}