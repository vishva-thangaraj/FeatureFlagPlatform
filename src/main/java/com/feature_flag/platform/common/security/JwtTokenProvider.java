package com.feature_flag.platform.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.List;

@Component
public class JwtTokenProvider {

    private final Key signingKey;
    private final JwtProperties jwtProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * The constructor Spring uses. Taking {@link JwtSecretPolicy} rather than reading
     * {@code jwtProperties.getSecret()} directly is what guarantees the startup guard runs <em>before</em>
     * a signing key can exist: a weak, published or missing secret fails the context, it does not quietly
     * become the key the platform signs with.
     */
    @Autowired
    public JwtTokenProvider(JwtProperties jwtProperties, JwtSecretPolicy secretPolicy) {
        this(jwtProperties, secretPolicy.getSecret());
    }

    /** Direct construction from an already-chosen secret; used by tests that supply their own key. */
    public JwtTokenProvider(JwtProperties jwtProperties) {
        this(jwtProperties, jwtProperties.getSecret());
    }

    private JwtTokenProvider(JwtProperties jwtProperties, String secret) {
        this.jwtProperties = jwtProperties;
        byte[] keyBytes = Base64.getDecoder().decode(secret);
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateAccessToken(Long userId, String email, Long tenantId, List<String> roles) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + jwtProperties.getAccessTokenExpirationMs());

        var builder = Jwts.builder()
                .setSubject(userId.toString())
                .claim("email", email)
                .setIssuedAt(now)
                .setExpiration(expiry);

        if (tenantId != null) {
            builder.claim("tenantId", tenantId);
        }
        builder.claim("roles", roles != null ? roles : Collections.emptyList());

        return builder.signWith(signingKey, SignatureAlgorithm.HS256).compact();
    }

    public String generateRefreshToken() {
        byte[] randomBytes = new byte[64];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    public long getRefreshTokenExpirationMs() {
        return jwtProperties.getRefreshTokenExpirationMs();
    }

    public long getAccessTokenExpirationMs() {
        return jwtProperties.getAccessTokenExpirationMs();
    }

    public Claims parseAccessToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public boolean validateAccessToken(String token) {
        try {
            parseAccessToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}