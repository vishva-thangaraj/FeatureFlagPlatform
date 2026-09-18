package com.feature_flag.platform.common.security.filter;

import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.auth.enums.UserStatus;
import com.feature_flag.platform.auth.repository.UserRepository;
import com.feature_flag.platform.common.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end proof that rate limiting sees the authenticated principal, through the real filter chain.
 *
 * <p>{@code RateLimitFilter} was registered <em>before</em> {@code JwtAuthenticationFilter} under a
 * {@code STATELESS} session policy, so {@code NullSecurityContextRepository} guaranteed an empty
 * {@code SecurityContext} at that point and its {@code "user:"} branch could never execute. Combined with
 * the empty {@code getServletPath()} under MockMvc, an authenticated caller rotating source addresses was
 * throttled by nothing at all: neither the endpoint's declared limit nor any per-caller allowance.
 *
 * <p>{@code POST /api/v1/tenants} declares {@code 10/min, burst 3}. Every request below comes from a
 * different address, which is exactly what defeated the old per-IP key.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PerUserRateLimitIntegrationTest {

    /** api-security.json: POST /api/v1/tenants, "throttle": { "requestsPerMinute": 10, "burstSize": 3 }. */
    private static final int CREATE_TENANT_BURST = 3;

    private static final AtomicInteger CLIENT_ADDRESS_SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;

    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() {
        User userA = userRepository.save(User.builder()
                .email("throttled-a@test.com").firstName("A").passwordHash("hash")
                .status(UserStatus.ACTIVE).build());
        User userB = userRepository.save(User.builder()
                .email("throttled-b@test.com").firstName("B").passwordHash("hash")
                .status(UserStatus.ACTIVE).build());

        // Exactly the token login issues to a user who belongs to no tenant yet.
        tokenA = jwtTokenProvider.generateAccessToken(userA.getId(), userA.getEmail(), null, List.of());
        tokenB = jwtTokenProvider.generateAccessToken(userB.getId(), userB.getEmail(), null, List.of());
    }

    /** Each call comes from a brand-new source address, the way a botnet or a mobile network would. */
    private MockHttpServletRequestBuilder createTenant(String token, String name) {
        return post("/api/v1/tenants")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"name\":\"" + name + "\"}")
                .with(request -> {
                    request.setRemoteAddr("198.51.100." + CLIENT_ADDRESS_SEQUENCE.incrementAndGet());
                    return request;
                });
    }

    @Test
    void authenticatedCaller_isThrottledPerUser_evenFromADifferentAddressEveryTime() throws Exception {
        for (int i = 0; i < CREATE_TENANT_BURST; i++) {
            mockMvc.perform(createTenant(tokenA, "Burst " + i))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(createTenant(tokenA, "One Too Many"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message").value("Rate limit exceeded. Try again later."));
    }

    /** A second user must not inherit the first one's exhausted bucket. */
    @Test
    void oneCallersExhaustedAllowance_doesNotThrottleAnother() throws Exception {
        for (int i = 0; i < CREATE_TENANT_BURST; i++) {
            mockMvc.perform(createTenant(tokenA, "A " + i)).andExpect(status().isCreated());
        }
        mockMvc.perform(createTenant(tokenA, "A overflow")).andExpect(status().isTooManyRequests());

        mockMvc.perform(createTenant(tokenB, "B first")).andExpect(status().isCreated());
    }
}
