package com.feature_flag.platform.common.security.filter;

import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.common.security.config.ApiSecurityConfigLoader;
import com.feature_flag.platform.common.security.config.EndpointSecurityRule;
import com.feature_flag.platform.common.security.config.ThrottleConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Token-bucket throttling, keyed by (endpoint rule, client).
 *
 * <p>Three properties this filter has to hold, each of which used to be broken:
 * <ul>
 *   <li><b>The client key must not be attacker-controlled.</b> {@code X-Forwarded-For} is only read when the
 *       request actually arrived from a configured trusted proxy, and even then only the hop the trusted
 *       infrastructure appended itself is used - see {@link #resolveClientIp}.</li>
 *   <li><b>The bucket key must be bounded.</b> Buckets are keyed by the matched rule PATTERN (not the concrete
 *       path, which made {@code /features/1}, {@code /features/2}, ... each get their own full allowance), the
 *       map is swept of replenished buckets, and it is hard-capped.</li>
 *   <li><b>Refill must be atomic.</b> Tokens and the refill timestamp move together under a single CAS, so
 *       concurrent requests cannot both apply the same refill window.</li>
 *   <li><b>An authenticated caller must be throttled as a caller, not as an address.</b> See
 *       {@link #resolveClientKey}; this filter runs after authentication so the principal is available.</li>
 * </ul>
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    /** Bucket scope used for requests no rule matches; deliberately a single scope so it cannot be inflated. */
    private static final String UNMATCHED_SCOPE = "*:<unmatched>";

    /** Longest plausible textual IP (IPv4-mapped IPv6 with a zone id fits well inside this). */
    private static final int MAX_IP_LENGTH = 45;

    /** Routine sweep cadence for replenished buckets. */
    private static final long SWEEP_INTERVAL_MS = 60_000L;

    /** Minimum gap between sweeps when the map is at capacity, so a flood cannot turn every request into a sweep. */
    private static final long CAPACITY_SWEEP_INTERVAL_MS = 1_000L;

    private final ApiSecurityConfigLoader configLoader;
    private final FilterErrorWriter errorWriter;
    private final List<IpAddressMatcher> trustedProxies;
    private final int proxyHopCount;
    private final int maxBuckets;
    private final LongSupplier clock;

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong lastSweepMs = new AtomicLong(0L);

    @Autowired
    public RateLimitFilter(ApiSecurityConfigLoader configLoader,
                           FilterErrorWriter errorWriter,
                           @Value("${app.security.rate-limit.trusted-proxies:}") String trustedProxies,
                           @Value("${app.security.rate-limit.proxy-hop-count:1}") int proxyHopCount,
                           @Value("${app.security.rate-limit.max-buckets:100000}") int maxBuckets) {
        this(configLoader, errorWriter, parseTrustedProxies(trustedProxies), proxyHopCount, maxBuckets,
                System::currentTimeMillis);
    }

    // Visible for testing: lets a test drive time and the bucket cap deterministically.
    RateLimitFilter(ApiSecurityConfigLoader configLoader,
                    FilterErrorWriter errorWriter,
                    List<IpAddressMatcher> trustedProxies,
                    int proxyHopCount,
                    int maxBuckets,
                    LongSupplier clock) {
        this.configLoader = configLoader;
        this.errorWriter = errorWriter;
        this.trustedProxies = List.copyOf(trustedProxies);
        this.proxyHopCount = Math.max(1, proxyHopCount);
        this.maxBuckets = Math.max(1, maxBuckets);
        this.clock = clock;
        if (this.trustedProxies.isEmpty()) {
            log.info("Rate limiting: no trusted proxies configured, X-Forwarded-For will be ignored "
                    + "(set app.security.rate-limit.trusted-proxies when running behind a reverse proxy)");
        } else {
            log.info("Rate limiting: trusting {} proxy range(s), reading hop {} from the right of "
                    + "X-Forwarded-For", this.trustedProxies.size(), this.proxyHopCount);
        }
    }

    /**
     * Parses the comma-separated trusted-proxy list. The default is EMPTY, which means "no proxy in front of
     * this app": {@code X-Forwarded-For} is then ignored entirely and only the real peer address is used, so a
     * directly exposed deployment is safe out of the box. A malformed entry fails startup rather than being
     * skipped, so a typo cannot quietly widen the trust boundary.
     */
    static List<IpAddressMatcher> parseTrustedProxies(String csv) {
        List<IpAddressMatcher> matchers = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return matchers;
        }
        for (String entry : csv.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                matchers.add(new IpAddressMatcher(trimmed));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "Invalid app.security.rate-limit.trusted-proxies entry '" + trimmed + "'", e);
            }
        }
        return matchers;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // RequestPaths, never getServletPath(): the latter is empty outside a servlet mapping, so every
        // request under MockMvc used to fall through to UNMATCHED_SCOPE and the per-endpoint throttles
        // (login 20/min, etc.) were never exercised by a test.
        String path = RequestPaths.of(request);
        String method = request.getMethod();

        Optional<EndpointSecurityRule> rule = configLoader.getRule(path, method);
        ThrottleConfig throttle = rule
                .map(EndpointSecurityRule::getThrottle)
                .orElseGet(configLoader::getDefaultThrottle);

        // Key by the rule PATTERN, never by the concrete path: otherwise every id under /features/*
        // mints its own bucket with a full burst allowance and id enumeration is unthrottled.
        String scope = rule
                .map(r -> r.getMethod().toUpperCase() + ":" + r.getPath())
                .orElse(UNMATCHED_SCOPE);
        String bucketKey = scope + "|" + resolveClientKey(request);

        long now = clock.getAsLong();
        sweepIfDue(now);

        TokenBucket bucket = buckets.get(bucketKey);
        if (bucket == null) {
            if (buckets.size() >= maxBuckets) {
                // Memory is finite: once the cap is reached, shed new keys instead of growing towards an OOM.
                // Every retained bucket is one that is actively consuming tokens.
                log.debug("Rate limit bucket capacity ({}) reached; rejecting new key", maxBuckets);
                errorWriter.write(response, HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded. Try again later.");
                return;
            }
            bucket = buckets.computeIfAbsent(bucketKey,
                    k -> new TokenBucket(throttle.getRequestsPerMinute(), throttle.getBurstSize(), now));
        }

        if (!bucket.tryConsume(now)) {
            errorWriter.write(response, HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded. Try again later.");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Drops every bucket that has refilled back to full. A full bucket is indistinguishable from a bucket that
     * was never created, so discarding it loses no rate-limiting state - that makes this both the TTL and the
     * capacity-relief mechanism, and it bounds the map to keys with traffic in the last refill window.
     */
    private void sweepIfDue(long now) {
        long last = lastSweepMs.get();
        long interval = buckets.size() >= maxBuckets ? CAPACITY_SWEEP_INTERVAL_MS : SWEEP_INTERVAL_MS;
        if (now - last < interval) {
            return;
        }
        if (!lastSweepMs.compareAndSet(last, now)) {
            return; // another thread is already sweeping
        }
        int before = buckets.size();
        buckets.values().removeIf(b -> b.isReplenished(now));
        int after = buckets.size();
        if (before != after) {
            log.debug("Rate limit sweep evicted {} replenished bucket(s), {} remain", before - after, after);
        }
        if (after >= maxBuckets) {
            // Throttled to at most one line per CAPACITY_SWEEP_INTERVAL_MS, so a flood cannot spam the log.
            log.warn("Rate limit bucket map is at its cap of {}; new client keys are being shed", maxBuckets);
        }
    }

    /**
     * The identity the bucket is keyed by.
     *
     * <p>An authenticated request is throttled as the USER, never as the address it came from. This filter
     * used to sit ahead of {@code JwtAuthenticationFilter} with a {@code STATELESS} session policy, so the
     * {@code SecurityContext} was guaranteed empty here and this branch was dead code: everything was
     * pooled per IP. That pooled every user behind a NAT or shared egress into one allowance (one noisy
     * colleague throttled the whole office) while a single abusive account spread over a handful of
     * addresses was never limited as an account at all. {@code SecurityConfig} now runs this filter
     * directly after authentication, so the principal is available.
     *
     * <p>Unauthenticated requests - which is every request to the credential endpoints, since
     * {@code JwtAuthenticationFilter} skips them outright - still key on the client address, so the
     * login/register/refresh brute-force limits declared in {@code api-security.json} apply exactly as
     * before.
     */
    private String resolveClientKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof TenantUserDetails userDetails
                && userDetails.getUserId() != null) {
            return "user:" + userDetails.getUserId();
        }
        return "ip:" + resolveClientIp(request);
    }

    /**
     * Resolves the address to throttle on.
     *
     * <p>{@code X-Forwarded-For} is client-supplied and is only meaningful given a known proxy topology.
     * nginx here uses {@code $proxy_add_x_forwarded_for}, which APPENDS the peer it saw to whatever the client
     * sent - so the trustworthy entry is the Nth from the RIGHT (N = number of trusted proxies that append),
     * never {@code split(",")[0]}, which is entirely attacker-chosen. When the request did not come from a
     * trusted proxy the header is ignored outright.
     */
    private String resolveClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (remoteAddr == null) {
            return "unknown";
        }
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }
        String forwarded = request.getHeader(X_FORWARDED_FOR);
        if (forwarded == null || forwarded.isBlank()) {
            return remoteAddr;
        }
        String[] hops = forwarded.split(",");
        int index = hops.length - proxyHopCount;
        if (index < 0) {
            // Fewer hops than the configured topology guarantees: nothing here is trustworthy.
            return remoteAddr;
        }
        String candidate = hops[index].trim();
        return isPlausibleIp(candidate) ? candidate : remoteAddr;
    }

    private boolean isTrustedProxy(String remoteAddr) {
        for (IpAddressMatcher matcher : trustedProxies) {
            try {
                if (matcher.matches(remoteAddr)) {
                    return true;
                }
            } catch (IllegalArgumentException e) {
                // Unparseable peer address: not something we can trust.
                return false;
            }
        }
        return false;
    }

    /** Keeps junk out of bucket keys: only characters that can appear in a textual IPv4/IPv6 address. */
    private static boolean isPlausibleIp(String value) {
        if (value.isEmpty() || value.length() > MAX_IP_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                    || c == '.' || c == ':' || c == '%';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    // Visible for testing.
    int bucketCount() {
        return buckets.size();
    }

    /**
     * Token bucket whose (tokens, lastRefill) pair moves as ONE immutable value under a CAS loop, so two
     * threads can never both apply the same refill window. Elapsed time is consumed in whole token intervals,
     * so the leftover fraction is carried forward instead of being thrown away.
     */
    private static final class TokenBucket {

        private record State(int tokens, long lastRefillMs) {
        }

        private final int maxTokens;
        private final double refillIntervalMs;
        private final AtomicReference<State> state;

        TokenBucket(int requestsPerMinute, int burstSize, long now) {
            this.maxTokens = Math.max(1, burstSize);
            this.refillIntervalMs = requestsPerMinute > 0 ? 60_000.0 / requestsPerMinute : Double.MAX_VALUE;
            this.state = new AtomicReference<>(new State(this.maxTokens, now));
        }

        boolean tryConsume(long now) {
            while (true) {
                State current = state.get();
                State refilled = refill(current, now);
                if (refilled.tokens() <= 0) {
                    if (refilled == current || state.compareAndSet(current, refilled)) {
                        return false;
                    }
                    continue;
                }
                State next = new State(refilled.tokens() - 1, refilled.lastRefillMs());
                if (state.compareAndSet(current, next)) {
                    return true;
                }
            }
        }

        /** True when the bucket is back at full allowance, i.e. it carries no state worth keeping. */
        boolean isReplenished(long now) {
            State current = state.get();
            State refilled = refill(current, now);
            if (refilled != current) {
                state.compareAndSet(current, refilled);
            }
            return refilled.tokens() >= maxTokens;
        }

        private State refill(State current, long now) {
            if (current.tokens() >= maxTokens) {
                // Already full: park the window at "now" so the idle time cannot later be cashed in twice.
                return current.lastRefillMs() == now ? current : new State(maxTokens, now);
            }
            long elapsed = now - current.lastRefillMs();
            if (elapsed <= 0) {
                return current; // clock did not move (or went backwards)
            }
            long earned = (long) (elapsed / refillIntervalMs);
            if (earned <= 0) {
                return current;
            }
            int tokens = (int) Math.min(maxTokens, (long) current.tokens() + earned);
            if (tokens >= maxTokens) {
                return new State(maxTokens, now);
            }
            // Advance only by the time the earned tokens actually cost; the remainder keeps accruing.
            long advance = (long) (earned * refillIntervalMs);
            return new State(tokens, current.lastRefillMs() + advance);
        }
    }
}
