package com.feature_flag.platform.auth.service;

import com.feature_flag.platform.auth.dto.AuthResponse;
import com.feature_flag.platform.auth.dto.RefreshRequest;
import com.feature_flag.platform.auth.entity.RefreshToken;
import com.feature_flag.platform.auth.entity.Role;
import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.auth.enums.UserStatus;
import com.feature_flag.platform.auth.mapper.AuthMapper;
import com.feature_flag.platform.auth.repository.RefreshTokenRepository;
import com.feature_flag.platform.common.config.I18N;
import com.feature_flag.platform.common.exception.InvalidTokenException;
import com.feature_flag.platform.common.exception.TenantAccessDeniedException;
import com.feature_flag.platform.common.exception.UserSuspendedException;
import com.feature_flag.platform.common.security.JwtProperties;
import com.feature_flag.platform.common.security.JwtTokenProvider;
import com.feature_flag.platform.common.security.TenantMembershipResolver;
import com.feature_flag.platform.common.security.TokenHashUtil;
import com.feature_flag.platform.tenant.entity.Membership;
import com.feature_flag.platform.tenant.entity.Tenant;
import com.feature_flag.platform.tenant.enums.MembershipStatus;
import com.feature_flag.platform.util.TestEntityUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenServiceTest {

    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private TenantMembershipResolver tenantMembershipResolver;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private JwtProperties jwtProperties;
    @Mock private AuthMapper authMapper;

    @InjectMocks private TokenServiceImpl tokenService;

    private MockedStatic<I18N> i18nMock;

    private User testUser;
    private Tenant testTenant;
    private Role ownerRole;
    private Membership activeMembership;

    @BeforeEach
    void setUp() {
        i18nMock = mockStatic(I18N.class);
        i18nMock.when(() -> I18N.getMessage(anyString())).thenAnswer(inv -> inv.getArgument(0));
        i18nMock.when(() -> I18N.getMessage(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));

        testUser = TestEntityUtil.withId(User.builder()
                .email("user@test.com").firstName("John").passwordHash("hash")
                .status(UserStatus.ACTIVE).build(), 1L);
        testTenant = TestEntityUtil.withId(Tenant.builder().name("TestOrg").build(), 100L);
        ownerRole = TestEntityUtil.withId(Role.builder().name("OWNER").build(), 10L);
        activeMembership = TestEntityUtil.withId(Membership.builder()
                .user(testUser).tenant(testTenant).status(MembershipStatus.ACTIVE)
                .roles(Set.of(ownerRole)).build(), 200L);

        when(jwtTokenProvider.generateAccessToken(any(), anyString(), any(), any())).thenReturn("access-token");
        when(jwtTokenProvider.generateRefreshToken()).thenReturn("new-refresh-token");
        when(jwtProperties.getRefreshTokenExpirationMs()).thenReturn(604_800_000L);
        when(jwtProperties.getAccessTokenExpirationMs()).thenReturn(900_000L);
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));
        when(authMapper.toAuthResponse(any(), any(), any(), any(), any(), anyLong()))
                .thenReturn(new AuthResponse("access-token", "new-refresh-token", 1L, "user@test.com", 100L, java.util.List.of("OWNER"), 900_000L));
    }

    @AfterEach
    void tearDown() { i18nMock.close(); }

    @Nested
    class Refresh {
        private RefreshToken validStoredToken;

        @BeforeEach
        void setUp() {
            validStoredToken = TestEntityUtil.withId(RefreshToken.builder()
                    .user(testUser).tenant(testTenant)
                    .tokenHash(TokenHashUtil.sha256("valid-refresh-token"))
                    .expiresAt(Instant.now().plusSeconds(3600)).revoked(false).build(), 300L);
        }

        @Test
        void success_rotatesTokenAndReturnsNewPair() {
            RefreshRequest request = new RefreshRequest("valid-refresh-token");
            String tokenHash = TokenHashUtil.sha256("valid-refresh-token");
            when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(validStoredToken));
            when(tenantMembershipResolver.requireActiveMembership(eq(1L), eq(100L), anyString(), anyString()))
                    .thenReturn(activeMembership);
            when(tenantMembershipResolver.roleNames(activeMembership)).thenReturn(java.util.List.of("OWNER"));

            AuthResponse response = tokenService.refresh(request);

            assertNotNull(response.accessToken());
            assertTrue(validStoredToken.isRevoked());
        }

        @Test
        void invalidToken_throwsInvalidTokenException() {
            RefreshRequest request = new RefreshRequest("unknown-token");
            String hash = TokenHashUtil.sha256("unknown-token");
            when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.empty());
            assertThrows(InvalidTokenException.class, () -> tokenService.refresh(request));
        }

        /**
         * Containment is user-wide, not tenant-wide: a replay proves the credential leaked, so the
         * user's sessions in every tenant go, not just the tenant the replayed token carried.
         */
        @Test
        void revokedToken_triggersReplayDetectionForEverySessionOfTheUser() {
            validStoredToken.setRevoked(true);
            RefreshRequest request = new RefreshRequest("valid-refresh-token");
            String hash = TokenHashUtil.sha256("valid-refresh-token");
            when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(validStoredToken));
            assertThrows(InvalidTokenException.class, () -> tokenService.refresh(request));
            verify(refreshTokenRepository).revokeAllByUserIdInNewTransaction(1L);
        }

        /** Tokens minted by register, or by login without an active membership, carry no tenant. */
        @Test
        void revokedTenantLessToken_stillTriggersReplayDetection() {
            RefreshToken tenantLessToken = TestEntityUtil.withId(RefreshToken.builder()
                    .user(testUser).tenant(null)
                    .tokenHash(TokenHashUtil.sha256("tenant-less-token"))
                    .expiresAt(Instant.now().plusSeconds(3600)).revoked(true).build(), 310L);
            RefreshRequest request = new RefreshRequest("tenant-less-token");
            when(refreshTokenRepository.findByTokenHash(TokenHashUtil.sha256("tenant-less-token")))
                    .thenReturn(Optional.of(tenantLessToken));

            assertThrows(InvalidTokenException.class, () -> tokenService.refresh(request));

            verify(refreshTokenRepository).revokeAllByUserIdInNewTransaction(1L);
        }

        /**
         * Revoking through the repository's own REQUIRES_NEW update rather than a dirty-checked
         * {@code save} in the doomed request transaction is the whole point of the fix, so this asserts
         * the call — {@link TokenServiceRefreshPersistenceIntegrationTest} asserts it actually commits.
         */
        @Test
        void expiredToken_revokesAndThrows() {
            validStoredToken.setExpiresAt(Instant.now().minusSeconds(60));
            RefreshRequest request = new RefreshRequest("valid-refresh-token");
            String hash = TokenHashUtil.sha256("valid-refresh-token");
            when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(validStoredToken));
            assertThrows(InvalidTokenException.class, () -> tokenService.refresh(request));
            verify(refreshTokenRepository).revokeByIdInNewTransaction(300L);
            verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
        }

        @Test
        void suspendedUser_isRefusedAndGetsNoNewTokens() {
            testUser.setStatus(UserStatus.SUSPENDED);
            RefreshRequest request = new RefreshRequest("valid-refresh-token");
            String hash = TokenHashUtil.sha256("valid-refresh-token");
            when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(validStoredToken));

            assertThrows(UserSuspendedException.class, () -> tokenService.refresh(request));

            verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
            verify(jwtTokenProvider, never()).generateAccessToken(any(), anyString(), any(), any());
        }

        @Test
        void membershipNoLongerExists_throwsTenantAccessDenied() {
            RefreshRequest request = new RefreshRequest("valid-refresh-token");
            String hash = TokenHashUtil.sha256("valid-refresh-token");
            when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(validStoredToken));
            when(tenantMembershipResolver.requireActiveMembership(eq(1L), eq(100L), anyString(), anyString()))
                    .thenThrow(new TenantAccessDeniedException("membership no longer exists"));
            assertThrows(TenantAccessDeniedException.class, () -> tokenService.refresh(request));
        }

        @Test
        void refreshReportsMembershipLossWithItsOwnMessageKeys() {
            RefreshRequest request = new RefreshRequest("valid-refresh-token");
            String hash = TokenHashUtil.sha256("valid-refresh-token");
            when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(validStoredToken));
            when(tenantMembershipResolver.requireActiveMembership(eq(1L), eq(100L), anyString(), anyString()))
                    .thenReturn(activeMembership);
            when(tenantMembershipResolver.roleNames(activeMembership)).thenReturn(java.util.List.of("OWNER"));

            tokenService.refresh(request);

            verify(tenantMembershipResolver).requireActiveMembership(1L, 100L,
                    com.feature_flag.platform.common.config.Messages.TENANT_MEMBERSHIP_NO_LONGER_EXISTS,
                    com.feature_flag.platform.common.config.Messages.TENANT_MEMBERSHIP_NO_LONGER_ACTIVE);
        }
    }

    @Nested
    class Logout {
        @Test
        void success_revokesToken() {
            RefreshToken token = TestEntityUtil.withId(RefreshToken.builder()
                    .user(testUser).tenant(testTenant)
                    .tokenHash(TokenHashUtil.sha256("logout-token"))
                    .expiresAt(Instant.now().plusSeconds(3600)).revoked(false).build(), 400L);

            RefreshRequest request = new RefreshRequest("logout-token");
            String hash = TokenHashUtil.sha256("logout-token");
            when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(token));

            tokenService.logout(request);

            assertTrue(token.isRevoked());
            verify(refreshTokenRepository).save(token);
        }

        @Test
        void unknownToken_doesNothing() {
            RefreshRequest request = new RefreshRequest("unknown-token");
            String hash = TokenHashUtil.sha256("unknown-token");
            when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.empty());
            tokenService.logout(request);
            verify(refreshTokenRepository, never()).save(any());
        }
    }
}
