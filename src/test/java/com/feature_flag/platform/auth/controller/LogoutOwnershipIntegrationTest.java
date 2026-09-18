package com.feature_flag.platform.auth.controller;

import com.feature_flag.platform.auth.entity.RefreshToken;
import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.auth.enums.UserStatus;
import com.feature_flag.platform.auth.repository.RefreshTokenRepository;
import com.feature_flag.platform.auth.repository.UserRepository;
import com.feature_flag.platform.common.security.JwtTokenProvider;
import com.feature_flag.platform.common.security.TokenHashUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end proof that logging out requires you to own the refresh token you present.
 *
 * <p>{@code TokenServiceImpl.logout} compares the authenticated caller against the token's owner, but that
 * comparison was inert: {@code /api/v1/auth/logout} was {@code authRequired:false} in api-security.json,
 * {@code permitAll} in {@code SecurityConfig}, and skipped outright by
 * {@code JwtAuthenticationFilter.shouldNotFilter}, so no principal was ever established and the endpoint
 * revoked whichever token it was handed. Anyone who obtained another user's refresh token could end that
 * user's session at will.
 *
 * <p>These tests go through the real filter chain, because the defect was in the chain configuration
 * rather than in the service: a service-level test passes either way.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LogoutOwnershipIntegrationTest {

    /** Rate-limit buckets are keyed partly by client address; a fresh one per request keeps them isolated. */
    private static final AtomicInteger CLIENT_ADDRESS_SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;

    private User alice;
    private User mallory;
    private String malloryAccessToken;

    /** The raw refresh token Alice holds — the credential Mallory is trying to revoke. */
    private String aliceRawRefreshToken;

    @BeforeEach
    void setUp() {
        alice = userRepository.save(User.builder()
                .email("alice@corp.com").firstName("Alice").passwordHash("hash")
                .status(UserStatus.ACTIVE).build());
        mallory = userRepository.save(User.builder()
                .email("mallory@evil.com").firstName("Mallory").passwordHash("hash")
                .status(UserStatus.ACTIVE).build());

        malloryAccessToken = jwtTokenProvider.generateAccessToken(
                mallory.getId(), mallory.getEmail(), null, List.of());

        aliceRawRefreshToken = jwtTokenProvider.generateRefreshToken();
        refreshTokenRepository.save(RefreshToken.builder()
                .user(alice)
                .tokenHash(TokenHashUtil.sha256(aliceRawRefreshToken))
                .expiresAt(Instant.now().plusSeconds(3600))
                .build());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder logoutWith(String rawToken) {
        return post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + rawToken + "\"}")
                .with(request -> {
                    request.setRemoteAddr("10.9.0." + CLIENT_ADDRESS_SEQUENCE.incrementAndGet());
                    return request;
                });
    }

    private boolean aliceTokenRevoked() {
        return refreshTokenRepository.findByTokenHash(TokenHashUtil.sha256(aliceRawRefreshToken))
                .orElseThrow()
                .isRevoked();
    }

    /**
     * The exploit: no credentials at all, just possession of the leaked token.
     *
     * <p>Asserts 403 rather than 401 because that is what this application actually returns: no
     * {@code AuthenticationEntryPoint} is configured, so Spring Security's default answer to an anonymous
     * caller on an authenticated endpoint is 403. Per HTTP semantics 401 would be the correct status for
     * "you did not authenticate" — that is a separate, application-wide fix, and pinning the real value
     * here keeps this test honest rather than asserting an aspiration.
     */
    @Test
    void unauthenticatedCaller_cannotRevokeSomeoneElsesSession() throws Exception {
        mockMvc.perform(logoutWith(aliceRawRefreshToken))
                .andExpect(status().isForbidden());

        assertThat(aliceTokenRevoked())
                .as("Alice's session must survive a logout attempt from an unauthenticated caller")
                .isFalse();
    }

    /** Authenticated, but as the wrong person. */
    @Test
    void authenticatedAsAnotherUser_cannotRevokeSomeoneElsesSession() throws Exception {
        mockMvc.perform(logoutWith(aliceRawRefreshToken)
                        .header("Authorization", "Bearer " + malloryAccessToken))
                .andExpect(status().isUnauthorized());

        assertThat(aliceTokenRevoked())
                .as("Alice's session must survive a logout attempt by Mallory")
                .isFalse();
    }

    /** The legitimate path still works — the fix must not break ordinary logout. */
    @Test
    void theOwnerCanStillLogOut() throws Exception {
        String aliceAccessToken = jwtTokenProvider.generateAccessToken(
                alice.getId(), alice.getEmail(), null, List.of());

        mockMvc.perform(logoutWith(aliceRawRefreshToken)
                        .header("Authorization", "Bearer " + aliceAccessToken))
                .andExpect(status().is2xxSuccessful());

        assertThat(aliceTokenRevoked())
                .as("Alice logging herself out must actually revoke her token")
                .isTrue();
    }
}
