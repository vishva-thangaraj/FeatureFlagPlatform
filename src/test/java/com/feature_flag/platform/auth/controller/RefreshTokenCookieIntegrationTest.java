package com.feature_flag.platform.auth.controller;

import com.feature_flag.platform.auth.entity.RefreshToken;
import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.auth.enums.UserStatus;
import com.feature_flag.platform.auth.repository.RefreshTokenRepository;
import com.feature_flag.platform.auth.repository.UserRepository;
import com.feature_flag.platform.common.security.JwtTokenProvider;
import com.feature_flag.platform.common.security.RefreshTokenCookies;
import com.feature_flag.platform.common.security.TokenHashUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Refresh tokens over an HttpOnly cookie, for clients that ask for one.
 *
 * <p>The protection only exists if the token leaves the response body when the cookie is used: a body
 * that still carried it would let script call {@code /refresh} and exfiltrate the rotated token, and the
 * cookie would be buying nothing. Most of what is asserted here is that absence.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RefreshTokenCookieIntegrationTest {

    private static final AtomicInteger CLIENT_ADDRESS_SEQUENCE = new AtomicInteger();
    private static final String COOKIE_NAME = "refresh_token";
    private static final String PASSWORD = "securePass123";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    private User user;
    private String rawRefreshToken;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.builder()
                .email("cookie@test.com").firstName("Cookie")
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .status(UserStatus.ACTIVE).build());

        rawRefreshToken = jwtTokenProvider.generateRefreshToken();
        refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .tokenHash(TokenHashUtil.sha256(rawRefreshToken))
                .expiresAt(Instant.now().plusSeconds(3600))
                .build());
    }

    // ------------------------------------------------------------------ issuing

    @Test
    @DisplayName("login on cookie transport sets the cookie and drops the token from the body")
    void loginOnCookieTransport() throws Exception {
        var result = mockMvc.perform(cookieTransport(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                // The whole point: nothing JavaScript can read.
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn();

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).startsWith(COOKIE_NAME + "=");
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("Secure");
        assertThat(setCookie).contains("SameSite=Strict");
        // Scoped to auth, so the credential is not attached to every dashboard request.
        assertThat(setCookie).contains("Path=/api/v1/auth");
        // Seven days, matching the token's own life.
        assertThat(setCookie).contains("Max-Age=604800");
    }

    @Test
    @DisplayName("a caller that does not ask is completely unaffected")
    void bodyTransportIsUnchanged() throws Exception {
        var result = mockMvc.perform(login())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    // ------------------------------------------------------------------ using it

    /**
     * A browser sends no body at all here — the cookie is the request. The rotated token must come back
     * as a new cookie, never in the body.
     */
    @Test
    @DisplayName("refresh works from the cookie alone, and rotates it")
    void refreshFromTheCookieAlone() throws Exception {
        var result = mockMvc.perform(withCookie(post("/api/v1/auth/refresh"), rawRefreshToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn();

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull().contains("HttpOnly");

        String reissued = valueOf(setCookie);
        assertThat(reissued).isNotEqualTo(rawRefreshToken);

        // Rotation still happens: the token that was presented is now spent.
        assertThat(refreshTokenRepository.findByTokenHash(TokenHashUtil.sha256(rawRefreshToken))
                .orElseThrow().isRevoked()).isTrue();
    }

    /** The header alone is enough on the first call, before any cookie exists. */
    @Test
    @DisplayName("refresh with a body token still answers in the body when not asked otherwise")
    void refreshOnBodyTransportIsUnchanged() throws Exception {
        mockMvc.perform(fresh(post("/api/v1/auth/refresh"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rawRefreshToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
    }

    @Test
    @DisplayName("refresh with neither a body nor a cookie is refused")
    void refreshWithNothingAtAll() throws Exception {
        mockMvc.perform(fresh(post("/api/v1/auth/refresh")))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ ending it

    /**
     * Logging out is the one moment where leaving a seven-day credential in the browser would be
     * indefensible, so the cookie is expired as well as the token revoked.
     */
    @Test
    @DisplayName("logout from the cookie revokes the token and expires the cookie")
    void logoutFromTheCookie() throws Exception {
        String accessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), user.getEmail(), null, List.of());

        var result = mockMvc.perform(withCookie(post("/api/v1/auth/logout"), rawRefreshToken)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(refreshTokenRepository.findByTokenHash(TokenHashUtil.sha256(rawRefreshToken))
                .orElseThrow().isRevoked()).isTrue();

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).contains("Max-Age=0");
        // Same name and path as the cookie it replaces, or the browser keeps the original.
        assertThat(setCookie).startsWith(COOKIE_NAME + "=");
        assertThat(setCookie).contains("Path=/api/v1/auth");
    }

    // ------------------------------------------------------------------ helpers

    private MockHttpServletRequestBuilder login() throws Exception {
        return fresh(post("/api/v1/auth/login"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        java.util.Map.of("email", user.getEmail(), "password", PASSWORD)));
    }

    private MockHttpServletRequestBuilder cookieTransport(MockHttpServletRequestBuilder builder) {
        return builder.header(RefreshTokenCookies.TRANSPORT_HEADER, "cookie");
    }

    private MockHttpServletRequestBuilder withCookie(MockHttpServletRequestBuilder builder, String value) {
        return fresh(builder).cookie(new Cookie(COOKIE_NAME, value));
    }

    /** Rate-limit buckets are keyed by caller; a new address per request keeps them isolated. */
    private MockHttpServletRequestBuilder fresh(MockHttpServletRequestBuilder builder) {
        return builder.with(request -> {
            request.setRemoteAddr("10.7.0." + (CLIENT_ADDRESS_SEQUENCE.incrementAndGet() % 250 + 1));
            return request;
        });
    }

    private String valueOf(String setCookieHeader) {
        String withoutName = setCookieHeader.substring((COOKIE_NAME + "=").length());
        int end = withoutName.indexOf(';');
        return end < 0 ? withoutName : withoutName.substring(0, end);
    }

}
