package com.feature_flag.platform.common.security.filter;

import com.feature_flag.platform.common.config.I18N;
import com.feature_flag.platform.common.config.Messages;
import com.feature_flag.platform.common.security.ApiKeyGenerator;
import com.feature_flag.platform.common.security.ApiKeyPrincipal;
import com.feature_flag.platform.common.security.TokenHashUtil;
import com.feature_flag.platform.tenant.dto.ApiKeyIdentity;
import com.feature_flag.platform.tenant.repository.ApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authenticates SDK callers presenting {@code X-API-Key}, on the SDK surface only.
 *
 * <p>Confined to {@link #SDK_PATH_PREFIX} by {@link #shouldNotFilter}. A key is not a dashboard
 * credential: the rest of the API reads {@code TenantUserDetails} out of the security context, so a key
 * accepted elsewhere would authenticate a request whose controller then fails on a principal of the
 * wrong type. Ignoring the header outside this prefix makes such a request plainly unauthenticated.
 *
 * <p>Runs after {@code JwtAuthenticationFilter} and before {@code RateLimitFilter}, so an SDK is
 * throttled per key rather than per address — otherwise every instance behind one egress shares a bucket.
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";

    /** Everything authenticated by key rather than by user. */
    public static final String SDK_PATH_PREFIX = "/api/v1/sdk/";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

    /** How often one key's usage stamp is written. A poll every 500ms must not be a write every 500ms. */
    private static final long LAST_USED_WRITE_INTERVAL_MS = 300_000L;

    /** Bounds the coalescing map: far above any plausible key count, and not attacker-growable. */
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final ApiKeyRepository apiKeyRepository;
    private final FilterErrorWriter errorWriter;
    private final Map<Long, Long> lastUsedWrites = new ConcurrentHashMap<>();

    public ApiKeyAuthenticationFilter(ApiKeyRepository apiKeyRepository, FilterErrorWriter errorWriter) {
        this.apiKeyRepository = apiKeyRepository;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !RequestPaths.of(request).startsWith(SDK_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String presented = request.getHeader(API_KEY_HEADER);
        if (presented == null || presented.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        // Two credentials, two identities, and no stated rule for which one acted. Refuse rather than
        // pick: a silent precedence order is the kind of thing an attacker gets to discover first.
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            unauthorized(response, Messages.API_KEY_AMBIGUOUS_CREDENTIALS);
            return;
        }

        Optional<ApiKeyIdentity> identity = resolve(presented);
        if (identity.isEmpty()) {
            // 401, not the 403 the rest of the API answers: an SDK has to tell a dead key, which it must
            // stop retrying, from a throttle, which it must retry with backoff.
            unauthorized(response, Messages.API_KEY_INVALID);
            return;
        }

        ApiKeyIdentity key = identity.get();
        ApiKeyPrincipal principal = new ApiKeyPrincipal(
                key.apiKeyId(), key.tenantId(), key.envId(), key.envName(), key.prefix());

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        recordUsage(key.apiKeyId());
        filterChain.doFilter(request, response);
    }

    private Optional<ApiKeyIdentity> resolve(String presented) {
        if (!ApiKeyGenerator.hasKeyShape(presented)) {
            return Optional.empty();
        }
        return apiKeyRepository.findActiveByKeyHash(TokenHashUtil.sha256(presented), Instant.now());
    }

    /**
     * Stamps {@code lastUsedAt}, at most once per interval per key, and never at the cost of the request:
     * a key that works must not stop working because a bookkeeping write failed.
     */
    private void recordUsage(Long apiKeyId) {
        long now = System.currentTimeMillis();
        Long previous = lastUsedWrites.get(apiKeyId);
        if (previous != null && now - previous < LAST_USED_WRITE_INTERVAL_MS) {
            return;
        }
        if (previous == null && lastUsedWrites.size() >= MAX_TRACKED_KEYS) {
            return;
        }
        lastUsedWrites.put(apiKeyId, now);
        try {
            apiKeyRepository.touchLastUsedAt(apiKeyId, Instant.ofEpochMilli(now));
        } catch (RuntimeException e) {
            log.debug("Could not stamp last-used for API key {}: {}", apiKeyId, e.getMessage());
        }
    }

    private void unauthorized(HttpServletResponse response, String messageKey) throws IOException {
        errorWriter.write(response, HttpStatus.UNAUTHORIZED, I18N.getMessage(messageKey));
    }
}
