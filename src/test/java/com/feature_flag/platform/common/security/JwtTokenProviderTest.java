package com.feature_flag.platform.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenProviderTest {

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        SecretKey key = Keys.secretKeyFor(SignatureAlgorithm.HS256);
        String base64Secret = Base64.getEncoder().encodeToString(key.getEncoded());

        JwtProperties props = new JwtProperties();
        props.setSecret(base64Secret);
        props.setAccessTokenExpirationMs(900_000);
        props.setRefreshTokenExpirationMs(604_800_000);

        provider = new JwtTokenProvider(props);
    }

    @Test
    void generateAccessToken_containsExpectedClaims() {
        String token = provider.generateAccessToken(42L, "user@test.com", 100L, List.of("OWNER", "ADMIN"));
        Claims claims = provider.parseAccessToken(token);

        assertEquals("42", claims.getSubject());
        assertEquals("user@test.com", claims.get("email", String.class));
        assertEquals(100, claims.get("tenantId", Integer.class));
        @SuppressWarnings("unchecked")
        List<String> roles = claims.get("roles", List.class);
        assertEquals(List.of("OWNER", "ADMIN"), roles);
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());
    }

    @Test
    void generateAccessToken_withNullTenantId_omitsTenantClaim() {
        String token = provider.generateAccessToken(1L, "u@t.com", null, List.of("VIEWER"));
        Claims claims = provider.parseAccessToken(token);
        assertNull(claims.get("tenantId"));
    }

    @Test
    void generateAccessToken_withNullRoles_setsEmptyList() {
        String token = provider.generateAccessToken(1L, "u@t.com", 2L, null);
        Claims claims = provider.parseAccessToken(token);
        @SuppressWarnings("unchecked")
        List<String> roles = claims.get("roles", List.class);
        assertTrue(roles.isEmpty());
    }

    @Test
    void generateAccessToken_withEmptyRoles() {
        String token = provider.generateAccessToken(1L, "u@t.com", 2L, Collections.emptyList());
        Claims claims = provider.parseAccessToken(token);
        @SuppressWarnings("unchecked")
        List<String> roles = claims.get("roles", List.class);
        assertTrue(roles.isEmpty());
    }

    @Test
    void validateAccessToken_validToken_returnsTrue() {
        String token = provider.generateAccessToken(1L, "u@t.com", 2L, List.of("OWNER"));
        assertTrue(provider.validateAccessToken(token));
    }

    @Test
    void validateAccessToken_tamperedToken_returnsFalse() {
        String token = provider.generateAccessToken(1L, "u@t.com", 2L, List.of("OWNER"));
        assertFalse(provider.validateAccessToken(token + "tampered"));
    }

    @Test
    void validateAccessToken_garbageToken_returnsFalse() {
        assertFalse(provider.validateAccessToken("not.a.jwt"));
    }

    @Test
    void validateAccessToken_emptyString_returnsFalse() {
        assertFalse(provider.validateAccessToken(""));
    }

    @Test
    void generateRefreshToken_returns86CharBase64Url() {
        String token = provider.generateRefreshToken();
        assertNotNull(token);
        assertEquals(86, token.length());
    }

    @Test
    void generateRefreshToken_uniqueEachTime() {
        String token1 = provider.generateRefreshToken();
        String token2 = provider.generateRefreshToken();
        assertNotEquals(token1, token2);
    }

    @Test
    void getExpirationMs_returnsConfiguredValues() {
        assertEquals(900_000, provider.getAccessTokenExpirationMs());
        assertEquals(604_800_000, provider.getRefreshTokenExpirationMs());
    }
}
