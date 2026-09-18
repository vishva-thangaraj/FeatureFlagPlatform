package com.feature_flag.platform.auth.service;

import com.feature_flag.platform.auth.dto.AuthResponse;
import com.feature_flag.platform.auth.dto.RefreshRequest;
import com.feature_flag.platform.auth.entity.RefreshToken;
import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.auth.enums.UserStatus;
import com.feature_flag.platform.auth.mapper.AuthMapper;
import com.feature_flag.platform.auth.repository.RefreshTokenRepository;
import com.feature_flag.platform.common.config.I18N;
import com.feature_flag.platform.common.config.Messages;
import com.feature_flag.platform.common.exception.ForbiddenException;
import com.feature_flag.platform.common.exception.InvalidTokenException;
import com.feature_flag.platform.common.exception.UserSuspendedException;
import com.feature_flag.platform.common.security.JwtProperties;
import com.feature_flag.platform.common.security.JwtTokenProvider;
import com.feature_flag.platform.common.security.TenantMembershipResolver;
import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.common.security.TokenHashUtil;
import com.feature_flag.platform.tenant.entity.Membership;
import com.feature_flag.platform.tenant.entity.Tenant;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
            // Replay of an already-rotated token: the refresh credential is in someone else's hands.
            // Every live session of this user dies, tenant-scoped or not — including the chain the
            // thief has been rotating. The revocation is committed in its own transaction because the
            // 401 below rolls this one back (see RefreshTokenRepository).
            refreshTokenRepository.revokeAllByUserIdInNewTransaction(storedToken.getUser().getId());
            throw new InvalidTokenException(I18N.getMessage(Messages.TOKEN_REVOKED));
        }

        if (storedToken.getExpiresAt().isBefore(Instant.now())) {
            // Same reasoning: burning the expired token must outlive the 401.
            refreshTokenRepository.revokeByIdInNewTransaction(storedToken.getId());
            throw new InvalidTokenException(I18N.getMessage(Messages.TOKEN_EXPIRED));
        }

        User user = storedToken.getUser();
        Tenant tenant = storedToken.getTenant();

        // Suspension has to bite before the next access token is minted, otherwise a user suspended
        // after login keeps renewing indefinitely and suspension is unenforceable short of deleting
        // rows by hand.
        //
        // Allowlist, not denylist, matching AuthServiceImpl.login: naming SUSPENDED alone let every other
        // status renew indefinitely — INACTIVE included — so refresh would have kept minting tokens for
        // an account login had already stopped accepting, and any status added later would fail open.
        if (user.getStatus() != UserStatus.ACTIVE) {
            if (user.getStatus() == UserStatus.SUSPENDED) {
                throw new UserSuspendedException(I18N.getMessage(Messages.AUTH_USER_SUSPENDED));
            }
            throw new ForbiddenException(I18N.getMessage(Messages.AUTH_USER_NOT_ACTIVE), "USER_NOT_ACTIVE");
        }

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

    /**
     * Revokes the presented refresh token, refusing to revoke one that belongs to somebody other than
     * the authenticated caller.
     *
     * <p><strong>Why the check exists.</strong> Logout used to be {@code authRequired:false},
     * {@code permitAll}, and skipped by {@code JwtAuthenticationFilter}, so no principal was established
     * and the endpoint revoked whatever refresh token it was handed — anyone holding a leaked token could
     * end another user's session. The endpoint is now authenticated in all three places, so a
     * {@link TenantUserDetails} principal is always present and this comparison is the ownership gate.
     * The check remains defensive about a missing principal rather than assuming one, so it cannot be
     * silently reduced to a no-op if that configuration is ever loosened again.
     *
     * <p>A mismatch is reported as a plain invalid-token 401 rather than a 403: saying "that token is
     * not yours" would confirm to the holder of a stolen token that the token is live and whose it is.
     *
     * <p>Note the access token is untouched — there is no denylist or token-version claim, so a JWT
     * already in flight stays valid until it expires. That is assessed, and deferred, in the report
     * accompanying this commit.
     */
    @Override
    @Transactional
    public void logout(RefreshRequest request) {
        String tokenHash = TokenHashUtil.sha256(request.refreshToken());
        refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(token -> {
            Long callerId = authenticatedUserId();
            if (callerId != null && !callerId.equals(token.getUser().getId())) {
                throw new InvalidTokenException(I18N.getMessage(Messages.TOKEN_INVALID));
            }
            token.setRevoked(true);
            refreshTokenRepository.save(token);
        });
    }

    /**
     * The id of the authenticated caller, or {@code null} when the request carries no authenticated
     * principal. Anonymous requests arrive with an {@code AnonymousAuthenticationToken} whose principal
     * is a {@code String}, so the pattern match rejects them as well as an entirely empty context.
     */
    private Long authenticatedUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof TenantUserDetails details) {
            return details.getUserId();
        }
        return null;
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