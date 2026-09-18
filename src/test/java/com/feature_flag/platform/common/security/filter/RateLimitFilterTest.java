package com.feature_flag.platform.common.security.filter;

import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.common.security.config.ApiSecurityConfigLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The security contract of {@link RateLimitFilter}: the bucket a request lands in is decided by the server,
 * not by the caller, and the number of buckets a caller can create is bounded.
 *
 * <p>Each test names a concrete bypass that used to work:
 * <ul>
 *   <li>a spoofed {@code X-Forwarded-For} minted a brand-new bucket with a full burst per request,</li>
 *   <li>keying by the concrete path gave every id under {@code /features/*} its own allowance,</li>
 *   <li>the bucket map had no eviction at all, so a client could allocate until the JVM died.</li>
 * </ul>
 * Rate limits come from the real {@code api-security.json}: POST /api/v1/auth/login is 20/min burst 5,
 * GET /api/v1/features/* is 60/min burst 10, and unmatched paths fall back to 60/min burst 10.
 */
class RateLimitFilterTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final int LOGIN_BURST = 5;
    private static final int FEATURES_BURST = 10;

    private static final String UNTRUSTED_CLIENT = "203.0.113.9";
    private static final String PROXY = "127.0.0.1";
    private static final String REAL_CLIENT = "198.51.100.7";

    private ApiSecurityConfigLoader configLoader;
    private FilterErrorWriter errorWriter;
    private AtomicLong clock;

    @BeforeEach
    void setUp() {
        configLoader = new ApiSecurityConfigLoader(new ObjectMapper(), "api-security.json");
        configLoader.load();
        errorWriter = new FilterErrorWriter(new ObjectMapper().registerModule(new JavaTimeModule()));
        clock = new AtomicLong(1_000_000L);
        SecurityContextHolder.clearContext();
    }

    private RateLimitFilter filter(List<String> trustedProxies, int hops, int maxBuckets) {
        return new RateLimitFilter(configLoader, errorWriter,
                trustedProxies.stream().map(IpAddressMatcher::new).toList(),
                hops, maxBuckets, clock::get);
    }

    private RateLimitFilter noProxyFilter() {
        return filter(List.of(), 1, 1000);
    }

    /** Sends one request and returns its status (200 = allowed through the chain, 429 = throttled). */
    private int send(RateLimitFilter filter, String method, String path, String remoteAddr, String forwardedFor)
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    private int allowedOf(RateLimitFilter filter, String method, String path, String remoteAddr,
                          java.util.function.IntFunction<String> forwardedFor, int attempts)
            throws ServletException, IOException {
        int allowed = 0;
        for (int i = 0; i < attempts; i++) {
            if (send(filter, method, path, remoteAddr, forwardedFor.apply(i)) == 200) {
                allowed++;
            }
        }
        return allowed;
    }

    // ------------------------------------------------------------------ X-Forwarded-For spoofing

    @Test
    void spoofedForwardedForFromUntrustedPeer_doesNotMintAFreshBucket() throws Exception {
        RateLimitFilter filter = noProxyFilter();

        // The login brute-force: a counter in the header used to buy a full burst allowance per request.
        int allowed = allowedOf(filter, "POST", LOGIN, UNTRUSTED_CLIENT, i -> "10.0.0." + i, 40);

        assertEquals(LOGIN_BURST, allowed,
                "no trusted proxy is configured, so X-Forwarded-For must be ignored entirely");
        assertEquals(1, filter.bucketCount(), "40 spoofed headers must not create 40 buckets");
    }

    @Test
    void spoofedForwardedForListFromUntrustedPeer_isIgnoredWholesale() throws Exception {
        RateLimitFilter filter = noProxyFilter();

        int allowed = allowedOf(filter, "POST", LOGIN, UNTRUSTED_CLIENT,
                i -> "1.2.3." + i + ", 4.5.6." + i + ", 7.8.9." + i, 40);

        assertEquals(LOGIN_BURST, allowed);
        assertEquals(1, filter.bucketCount());
    }

    @Test
    void behindATrustedProxy_onlyTheHopTheProxyAppendedCounts() throws Exception {
        RateLimitFilter filter = filter(List.of(PROXY + "/32"), 1, 1000);

        // nginx's $proxy_add_x_forwarded_for appends the peer it saw, so the header is
        // "<whatever the client sent>, <real client ip>". Everything left of the last hop is spoofable.
        int allowed = allowedOf(filter, "POST", LOGIN, PROXY, i -> "10.0.0." + i + ", " + REAL_CLIENT, 40);

        assertEquals(LOGIN_BURST, allowed, "the spoofed prefix must not change the bucket");
        assertEquals(1, filter.bucketCount());
    }

    @Test
    void behindATrustedProxy_distinctRealClientsStillGetTheirOwnBucket() throws Exception {
        RateLimitFilter filter = filter(List.of(PROXY + "/32"), 1, 1000);

        for (int i = 0; i < LOGIN_BURST; i++) {
            assertEquals(200, send(filter, "POST", LOGIN, PROXY, "spoof, " + REAL_CLIENT));
        }

        assertEquals(429, send(filter, "POST", LOGIN, PROXY, "spoof, " + REAL_CLIENT));
        assertEquals(200, send(filter, "POST", LOGIN, PROXY, "spoof, 198.51.100.8"),
                "a different real client must not inherit the exhausted bucket");
        assertEquals(2, filter.bucketCount());
    }

    @Test
    void trustedProxyWithFewerHopsThanConfigured_fallsBackToThePeerAddress() throws Exception {
        // Two appending proxies configured, but the header only carries one entry: nothing in it is
        // guaranteed to have been written by trusted infrastructure, so it is discarded.
        RateLimitFilter filter = filter(List.of(PROXY + "/32"), 2, 1000);

        int allowed = allowedOf(filter, "POST", LOGIN, PROXY, i -> "10.0.0." + i, 40);

        assertEquals(LOGIN_BURST, allowed);
        assertEquals(1, filter.bucketCount());
    }

    @Test
    void trustedProxyWithTwoHops_readsTheSecondEntryFromTheRight() throws Exception {
        RateLimitFilter filter = filter(List.of(PROXY + "/32"), 2, 1000);

        // edge LB -> nginx -> app: "<spoof>, <client seen by LB>, <LB seen by nginx>"
        for (int i = 0; i < LOGIN_BURST; i++) {
            assertEquals(200, send(filter, "POST", LOGIN, PROXY, "spoof" + i + ", " + REAL_CLIENT + ", 10.1.1.1"));
        }

        assertEquals(429, send(filter, "POST", LOGIN, PROXY, "other, " + REAL_CLIENT + ", 10.1.1.1"));
        assertEquals(200, send(filter, "POST", LOGIN, PROXY, "other, 198.51.100.8, 10.1.1.1"));
    }

    @Test
    void garbageForwardedForFromATrustedProxy_doesNotBecomeABucketKey() throws Exception {
        RateLimitFilter filter = filter(List.of(PROXY + "/32"), 1, 1000);

        int allowed = allowedOf(filter, "POST", LOGIN, PROXY, i -> "spoof, not-an-ip-" + i, 40);

        assertEquals(LOGIN_BURST, allowed, "unparseable hops fall back to the proxy peer address");
        assertEquals(1, filter.bucketCount());
    }

    @Test
    void requestFromAnUntrustedPeerThatIsNotTheProxy_cannotUseTheHeader() throws Exception {
        RateLimitFilter filter = filter(List.of("10.8.0.0/24"), 1, 1000);

        int allowed = allowedOf(filter, "POST", LOGIN, UNTRUSTED_CLIENT, i -> "1.1.1." + i + ", 2.2.2." + i, 40);

        assertEquals(LOGIN_BURST, allowed);
        assertEquals(1, filter.bucketCount());
    }

    // ------------------------------------------------------------------ per-pattern bucket keying

    @Test
    void idEnumerationSharesASingleBucketPerRulePattern() throws Exception {
        RateLimitFilter filter = noProxyFilter();

        // /features/1, /features/2, ... all match GET /api/v1/features/* and must share its 60/min bucket.
        int allowed = 0;
        for (int i = 1; i <= 40; i++) {
            if (send(filter, "GET", "/api/v1/features/" + i, UNTRUSTED_CLIENT, null) == 200) {
                allowed++;
            }
        }

        assertEquals(FEATURES_BURST, allowed, "walking ids must not reset the throttle");
        assertEquals(1, filter.bucketCount());
    }

    @Test
    void differentRulePatternsKeepSeparateBuckets() throws Exception {
        RateLimitFilter filter = noProxyFilter();

        for (int i = 1; i <= FEATURES_BURST; i++) {
            assertEquals(200, send(filter, "GET", "/api/v1/features/" + i, UNTRUSTED_CLIENT, null));
        }

        assertEquals(429, send(filter, "GET", "/api/v1/features/99", UNTRUSTED_CLIENT, null));
        assertEquals(200, send(filter, "GET", "/api/v1/members/99", UNTRUSTED_CLIENT, null),
                "a different endpoint rule has its own allowance");
        assertEquals(2, filter.bucketCount());
    }

    @Test
    void pathsWithNoRuleShareOneDefaultBucket() throws Exception {
        RateLimitFilter filter = noProxyFilter();

        int allowed = 0;
        for (int i = 0; i < 40; i++) {
            if (send(filter, "GET", "/api/v1/unmapped/" + i, UNTRUSTED_CLIENT, null) == 200) {
                allowed++;
            }
        }

        assertEquals(FEATURES_BURST, allowed, "unmatched paths fall back to the default throttle, once");
        assertEquals(1, filter.bucketCount(), "an unmapped path must not be a bucket factory");
    }

    // ------------------------------------------------------------------ bounded memory

    @Test
    void bucketMapIsHardCappedSoOneClientCannotExhaustMemory() throws Exception {
        RateLimitFilter filter = filter(List.of(), 1, 5);

        for (int i = 0; i < 2000; i++) {
            send(filter, "POST", LOGIN, "198.18." + (i / 250) + "." + (i % 250), null);
            assertTrue(filter.bucketCount() <= 5,
                    "bucket map grew past its cap at iteration " + i + ": " + filter.bucketCount());
        }
    }

    @Test
    void newKeysAreRejectedRatherThanAllocatedOnceTheCapIsReached() throws Exception {
        RateLimitFilter filter = filter(List.of(), 1, 2);

        assertEquals(200, send(filter, "POST", LOGIN, "198.18.0.1", null));
        assertEquals(200, send(filter, "POST", LOGIN, "198.18.0.2", null));
        assertEquals(429, send(filter, "POST", LOGIN, "198.18.0.3", null),
                "at capacity, an unseen key is shed instead of allocated");
        assertEquals(2, filter.bucketCount());
    }

    @Test
    void replenishedBucketsAreSweptSoTheMapShrinksAgain() throws Exception {
        RateLimitFilter filter = filter(List.of(), 1, 1000);

        for (int i = 0; i < 50; i++) {
            send(filter, "POST", LOGIN, "198.18.1." + i, null);
        }
        assertEquals(50, filter.bucketCount());

        // Well past both the sweep interval and a full refill of the 20/min login bucket.
        clock.addAndGet(10 * 60_000L);
        send(filter, "POST", LOGIN, "198.18.9.9", null);

        assertEquals(1, filter.bucketCount(),
                "buckets back at full allowance carry no state and must be evicted");
    }

    @Test
    void sweepDoesNotDropBucketsThatAreStillThrottling() throws Exception {
        RateLimitFilter filter = filter(List.of(), 1, 2);

        assertEquals(200, send(filter, "POST", LOGIN, "198.18.0.1", null));
        assertEquals(200, send(filter, "POST", LOGIN, "198.18.0.2", null));

        // At capacity a sweep runs, but 1.5s is less than one login token (20/min == one per 3s), so
        // neither bucket has replenished and neither may be discarded.
        clock.addAndGet(1_500L);
        assertEquals(429, send(filter, "POST", LOGIN, "198.18.0.3", null));
        assertEquals(2, filter.bucketCount());

        // ...and the first client's partially consumed allowance survived the sweep intact.
        for (int i = 1; i < LOGIN_BURST; i++) {
            assertEquals(200, send(filter, "POST", LOGIN, "198.18.0.1", null));
        }
        assertEquals(429, send(filter, "POST", LOGIN, "198.18.0.1", null));
    }

    // ------------------------------------------------------------------ refill correctness

    @Test
    void refillCarriesTheFractionalRemainderInsteadOfDiscardingIt() throws Exception {
        RateLimitFilter filter = noProxyFilter();
        long start = clock.get();

        for (int i = 0; i < LOGIN_BURST; i++) {
            assertEquals(200, send(filter, "POST", LOGIN, UNTRUSTED_CLIENT, null));
        }
        assertEquals(429, send(filter, "POST", LOGIN, UNTRUSTED_CLIENT, null));

        // 20/min == one token per 3s. At 4.5s exactly one token is due; the leftover 1.5s must carry over.
        clock.set(start + 4_500);
        assertEquals(200, send(filter, "POST", LOGIN, UNTRUSTED_CLIENT, null));

        // At 6s a second token is due. Resetting the refill window to "now" on the previous refill would
        // have thrown that 1.5s away and rejected this request.
        clock.set(start + 6_000);
        assertEquals(200, send(filter, "POST", LOGIN, UNTRUSTED_CLIENT, null));

        clock.set(start + 6_500);
        assertEquals(429, send(filter, "POST", LOGIN, UNTRUSTED_CLIENT, null),
                "and no token may be handed out early");
    }

    @Test
    void anIdleBucketDoesNotBankUnlimitedTokens() throws Exception {
        RateLimitFilter filter = noProxyFilter();

        assertEquals(200, send(filter, "POST", LOGIN, UNTRUSTED_CLIENT, null));
        clock.addAndGet(60 * 60_000L); // an hour of idling
        assertEquals(LOGIN_BURST, allowedOf(filter, "POST", LOGIN, UNTRUSTED_CLIENT, i -> null, 40),
                "refill is capped at the burst size, not at the elapsed time");
    }

    @Test
    void concurrentRequestsCannotBothApplyTheSameRefillWindow() throws Exception {
        // Refill used to be read-compute-write across separate atomics, so racing threads could each observe
        // the same stale timestamp and each add the same tokens. Repeated rounds to make the race show up.
        for (int round = 0; round < 20; round++) {
            RateLimitFilter filter = noProxyFilter();
            String path = "/api/v1/unmapped/x"; // default throttle: 60/min (1 token/s), burst 10
            long start = clock.get();

            for (int i = 0; i < FEATURES_BURST; i++) {
                assertEquals(200, send(filter, "GET", path, UNTRUSTED_CLIENT, null));
            }
            assertEquals(429, send(filter, "GET", path, UNTRUSTED_CLIENT, null));

            clock.set(start + 3_000); // exactly three tokens are due

            int threads = 8;
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger allowed = new AtomicInteger();
            for (int t = 0; t < threads; t++) {
                new Thread(() -> {
                    try {
                        ready.countDown();
                        go.await();
                        for (int i = 0; i < 5; i++) {
                            if (send(filter, "GET", path, UNTRUSTED_CLIENT, null) == 200) {
                                allowed.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        done.countDown();
                    }
                }).start();
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));

            assertEquals(3, allowed.get(),
                    "round " + round + ": three seconds of refill must yield exactly three tokens");
            clock.set(start + 3_000);
        }
    }

    // ------------------------------------------------------------------ who the bucket belongs to

    /**
     * Authenticates the thread as {@code userId}, exactly as JwtAuthenticationFilter and
     * TenantContextFilter leave the context by the time this filter now runs.
     */
    private void authenticateAs(long userId) {
        TenantUserDetails principal =
                new TenantUserDetails(userId, "u" + userId + "@test.com", 1L, List.of("VIEWER"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    /**
     * The regression for the dead per-user branch. This filter used to be registered ahead of
     * JwtAuthenticationFilter under a STATELESS session policy, so the SecurityContext was guaranteed
     * empty and every request was keyed by address: an abusive account simply moved between addresses and
     * was never limited as an account. It now runs after authentication, so the principal decides.
     */
    @Test
    void authenticatedCaller_isThrottledAcrossEveryAddressTheyUse() throws Exception {
        RateLimitFilter filter = noProxyFilter();
        authenticateAs(7L);

        // A different source address on every single request - the old per-IP key made each of these a
        // brand-new bucket with a full burst.
        int allowed = 0;
        for (int i = 0; i < 40; i++) {
            if (send(filter, "POST", LOGIN, "203.0.113." + i, null) == 200) {
                allowed++;
            }
        }

        assertEquals(LOGIN_BURST, allowed, "an authenticated caller is throttled as a caller, not per IP");
        assertEquals(1, filter.bucketCount(), "40 addresses, one user, one bucket");
    }

    /** Two authenticated callers must not share an allowance, even from the very same address. */
    @Test
    void twoAuthenticatedCallersFromOneAddress_doNotShareAnAllowance() throws Exception {
        RateLimitFilter filter = noProxyFilter();

        authenticateAs(1L);
        assertEquals(LOGIN_BURST, allowedOf(filter, "POST", LOGIN, REAL_CLIENT, i -> null, 40));

        // Same NAT, same egress address, different person: their allowance is untouched. Pooling these
        // into one IP bucket is what let one noisy colleague throttle a whole office.
        authenticateAs(2L);
        assertEquals(LOGIN_BURST, allowedOf(filter, "POST", LOGIN, REAL_CLIENT, i -> null, 40));

        assertEquals(2, filter.bucketCount(), "one bucket per user, not one per address");
    }

    /**
     * The half that must NOT change: the credential endpoints are unauthenticated by definition, so the
     * per-IP brute-force limit api-security.json declares for them (login 20/min, burst 5) still applies.
     */
    @Test
    void unauthenticatedCaller_isStillThrottledPerAddress() throws Exception {
        RateLimitFilter filter = noProxyFilter();
        SecurityContextHolder.clearContext();

        assertEquals(LOGIN_BURST, allowedOf(filter, "POST", LOGIN, REAL_CLIENT, i -> null, 40),
                "login must keep its 20/min, burst 5 brute-force limit for anonymous callers");

        // A second address is a second unauthenticated client, so it gets its own allowance as before.
        assertEquals(LOGIN_BURST, allowedOf(filter, "POST", LOGIN, UNTRUSTED_CLIENT, i -> null, 40));
    }

    /** An anonymous token (no TenantUserDetails principal) must not be mistaken for an authenticated one. */
    @Test
    void anonymousAuthentication_fallsBackToTheAddressKey() throws Exception {
        RateLimitFilter filter = noProxyFilter();
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        int allowed = 0;
        for (int i = 0; i < 40; i++) {
            if (send(filter, "POST", LOGIN, "203.0.113." + i, null) == 200) {
                allowed++;
            }
        }

        assertEquals(40, allowed, "each anonymous address is its own client, exactly as before");
    }

    // ------------------------------------------------------------------ which rule the request matched

    /**
     * The filter matched rules on getServletPath(), which is empty for any request that did not go through
     * a servlet mapping - every request under MockMvc. So the per-endpoint throttles were never exercised
     * by a test: everything fell through to the unmatched-scope default of burst 10.
     */
    @Test
    void theRuleIsMatchedFromTheRequestUri_notTheServletPath() throws Exception {
        RateLimitFilter filter = noProxyFilter();

        MockHttpServletRequest probe = new MockHttpServletRequest("POST", LOGIN);
        probe.setServletPath("");
        assertEquals("", probe.getServletPath(), "precondition: this is what MockMvc looks like");

        int allowed = 0;
        for (int i = 0; i < 40; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", LOGIN);
            request.setServletPath("");
            request.setRemoteAddr(REAL_CLIENT);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            if (response.getStatus() == 200) {
                allowed++;
            }
        }

        assertEquals(LOGIN_BURST, allowed,
                "login's burst of 5 must apply even when getServletPath() is empty");
    }
}
