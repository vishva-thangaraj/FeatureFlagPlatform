package com.feature_flag.platform.auth.service;

import com.feature_flag.platform.auth.dto.AuthResponse;
import com.feature_flag.platform.auth.dto.RefreshRequest;
import com.feature_flag.platform.auth.entity.RefreshToken;
import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.auth.mapper.AuthMapper;
import com.feature_flag.platform.auth.repository.RefreshTokenRepository;
import com.feature_flag.platform.common.config.I18N;
import com.feature_flag.platform.common.config.Messages;
import com.feature_flag.platform.common.exception.InvalidTokenException;
import com.feature_flag.platform.common.security.JwtProperties;
import com.feature_flag.platform.common.security.JwtTokenProvider;
import com.feature_flag.platform.common.security.TenantMembershipResolver;
import com.feature_flag.platform.common.security.TokenHashUtil;
import com.feature_flag.platform.tenant.entity.Membership;
import com.feature_flag.platform.tenant.entity.Tenant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Service
public class TokenServiceImpl implements TokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final TenantMembershipResolver tenantMembershipResolver;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final AuthMapper authMapper;

    public TokenServiceImpl(RefreshTokenRepository refreshTokenRepository,
                            TenantMembershipResolver tenantMembershipResolver,
                            JwtTokenProvider jwtTokenProvider,
                            JwtProperties jwtProperties,
                            AuthMapper authMapper) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.tenantMembershipResolver = tenantMembershipResolver;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
        this.authMapper = authMapper;
    }

    @Override
    @Transactional
    public AuthResponse refresh(RefreshRequest request) {
        String tokenHash = TokenHashUtil.sha256(request.refreshToken());
        RefreshToken storedToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidTokenException(
                        I18N.getMessage(Messages.TOKEN_INVALID)));

        if (storedToken.isRevoked()) {
            if (storedToken.getTenant() != null) {
                refreshTokenRepository.revokeAllByUserIdAndTenantId(
                        storedToken.getUser().getId(), storedToken.getTenant().getId());
            }
            throw new InvalidTokenException(I18N.getMessage(Messages.TOKEN_REVOKED));
        }

        if (storedToken.getExpiresAt().isBefore(Instant.now())) {
            storedToken.setRevoked(true);
            refreshTokenRepository.save(storedToken);
            throw new InvalidTokenException(I18N.getMessage(Messages.TOKEN_EXPIRED));
        }

        User user = storedToken.getUser();
        Tenant tenant = storedToken.getTenant();

        List<String> roles;
        if (tenant != null) {
            Membership membership = tenantMembershipResolver.requireActiveMembership(
                    user.getId(), tenant.getId(),
                    Messages.TENANT_MEMBERSHIP_NO_LONGER_EXISTS, Messages.TENANT_MEMBERSHIP_NO_LONGER_ACTIVE);
            roles = tenantMembershipResolver.roleNames(membership);
        } else {
            roles = Collections.emptyList();
        }

        String newRawRefreshToken = jwtTokenProvider.generateRefreshToken();
        String newTokenHash = TokenHashUtil.sha256(newRawRefreshToken);

        RefreshToken newRefreshToken = RefreshToken.builder()
                .user(user)
                .tenant(tenant)
                .tokenHash(newTokenHash)
                .expiresAt(Instant.now().plusMillis(jwtProperties.getRefreshTokenExpirationMs()))
                .build();
        newRefreshToken = refreshTokenRepository.save(newRefreshToken);

        storedToken.setRevoked(true);
        storedToken.setReplacedBy(newRefreshToken);
        refreshTokenRepository.save(storedToken);

        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(),
                tenant != null ? tenant.getId() : null, roles);

        return authMapper.toAuthResponse(user, tenant, roles, accessToken, newRawRefreshToken,
                jwtProperties.getAccessTokenExpirationMs());
    }

    @Override
    @Transactional
    public void logout(RefreshRequest request) {
        String tokenHash = TokenHashUtil.sha256(request.refreshToken());
        refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(token -> {
            token.setRevoked(true);
            refreshTokenRepository.save(token);
        });
    }

    @Override
    @Transactional
    public AuthResponse createTokenPair(User user, Tenant tenant, List<String> roles) {
        Long tenantId = tenant != null ? tenant.getId() : null;
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), tenantId, roles);
        String rawRefreshToken = jwtTokenProvider.generateRefreshToken();
        String tokenHash = TokenHashUtil.sha256(rawRefreshToken);

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tenant(tenant)
                .tokenHash(tokenHash)
                .expiresAt(Instant.now().plusMillis(jwtProperties.getRefreshTokenExpirationMs()))
                .build();
        refreshTokenRepository.save(refreshToken);

        return authMapper.toAuthResponse(user, tenant, roles, accessToken, rawRefreshToken,
                jwtProperties.getAccessTokenExpirationMs());
    }
}