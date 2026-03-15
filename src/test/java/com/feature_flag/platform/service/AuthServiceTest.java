package com.feature_flag.platform.service;

import com.feature_flag.platform.config.I18N;
import com.feature_flag.platform.config.JwtProperties;
import com.feature_flag.platform.dto.auth.AuthResponse;
import com.feature_flag.platform.dto.auth.CreateTenantRequest;
import com.feature_flag.platform.dto.auth.LoginRequest;
import com.feature_flag.platform.dto.auth.RefreshRequest;
import com.feature_flag.platform.dto.auth.RegisterRequest;
import com.feature_flag.platform.dto.auth.SwitchTenantRequest;
import com.feature_flag.platform.entity.Membership;
import com.feature_flag.platform.entity.RefreshToken;
import com.feature_flag.platform.entity.Role;
import com.feature_flag.platform.entity.Tenant;
import com.feature_flag.platform.entity.User;
import com.feature_flag.platform.enums.MembershipStatus;
import com.feature_flag.platform.enums.UserStatus;
import com.feature_flag.platform.exception.EmailAlreadyExistsException;
import com.feature_flag.platform.exception.InvalidCredentialsException;
import com.feature_flag.platform.exception.InvalidTokenException;
import com.feature_flag.platform.exception.ResourceNotFoundException;
import com.feature_flag.platform.exception.TenantAccessDeniedException;
import com.feature_flag.platform.exception.UserSuspendedException;
import com.feature_flag.platform.repository.MembershipRepository;
import com.feature_flag.platform.repository.RefreshTokenRepository;
import com.feature_flag.platform.repository.RoleRepository;
import com.feature_flag.platform.repository.TenantRepository;
import com.feature_flag.platform.repository.UserRepository;
import com.feature_flag.platform.security.JwtTokenProvider;
import com.feature_flag.platform.security.TokenHashUtil;
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
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private MembershipRepository membershipRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private JwtProperties jwtProperties;
    @Mock private PasswordEncoder passwordEncoder;

    @InjectMocks private AuthService authService;

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
                .email("user@test.com")
                .firstName("John")
                .lastName("Doe")
                .passwordHash("encoded-pass")
                .status(UserStatus.ACTIVE)
                .build(), 1L);

        testTenant = TestEntityUtil.withId(Tenant.builder()
                .name("TestOrg")
                .build(), 100L);

        ownerRole = TestEntityUtil.withId(Role.builder()
                .name("OWNER")
                .build(), 10L);

        activeMembership = TestEntityUtil.withId(Membership.builder()
                .user(testUser)
                .tenant(testTenant)
                .status(MembershipStatus.ACTIVE)
                .roles(Set.of(ownerRole))
                .build(), 200L);

        when(jwtTokenProvider.generateAccessToken(any(), anyString(), any(), any())).thenReturn("access-token");
        when(jwtTokenProvider.generateRefreshToken()).thenReturn("refresh-token");
        when(jwtProperties.getRefreshTokenExpirationMs()).thenReturn(604_800_000L);
        when(jwtProperties.getAccessTokenExpirationMs()).thenReturn(900_000L);
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        i18nMock.close();
    }

    // ========== register ==========
    @Nested
    class Register {

        @Test
        void success_createsUserWithoutTenant() {
            RegisterRequest request = new RegisterRequest("new@test.com", "password123", "John", null);
            when(userRepository.existsByEmail("new@test.com")).thenReturn(false);
            when(passwordEncoder.encode("password123")).thenReturn("encoded");
            when(userRepository.save(any(User.class))).thenReturn(testUser);

            AuthResponse response = authService.register(request);

            assertNotNull(response.accessToken());
            assertNotNull(response.refreshToken());
            assertEquals(testUser.getId(), response.userId());
            assertEquals(testUser.getEmail(), response.email());
            assertNull(response.tenantId());
            assertTrue(response.roles().isEmpty());
            verify(refreshTokenRepository).save(any(RefreshToken.class));
        }

        @Test
        void duplicateEmail_throwsEmailAlreadyExistsException() {
            RegisterRequest request = new RegisterRequest("dup@test.com", "pass12345", "Jane", null);
            when(userRepository.existsByEmail("dup@test.com")).thenReturn(true);

            assertThrows(EmailAlreadyExistsException.class, () -> authService.register(request));
            verify(userRepository, never()).save(any());
        }
    }

    // ========== login ==========
    @Nested
    class Login {

        @Test
        void success_withMembership_returnsTokensWithTenant() {
            LoginRequest request = new LoginRequest("user@test.com", "pass", null);
            when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(testUser));
            when(passwordEncoder.matches("pass", "encoded-pass")).thenReturn(true);
            when(membershipRepository.findByUserId(1L)).thenReturn(List.of(activeMembership));

            AuthResponse response = authService.login(request);

            assertEquals(testTenant.getId(), response.tenantId());
            assertEquals(List.of("OWNER"), response.roles());
        }

        @Test
        void success_withSpecificTenantId() {
            LoginRequest request = new LoginRequest("user@test.com", "pass", 100L);
            when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(testUser));
            when(passwordEncoder.matches("pass", "encoded-pass")).thenReturn(true);
            when(membershipRepository.findByUserId(1L)).thenReturn(List.of(activeMembership));

            AuthResponse response = authService.login(request);

            assertEquals(100L, response.tenantId());
        }

        @Test
        void success_noMemberships_returnsTokenWithoutTenant() {
            LoginRequest request = new LoginRequest("user@test.com", "pass", null);
            when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(testUser));
            when(passwordEncoder.matches("pass", "encoded-pass")).thenReturn(true);
            when(membershipRepository.findByUserId(1L)).thenReturn(Collections.emptyList());

            AuthResponse response = authService.login(request);

            assertNull(response.tenantId());
            assertTrue(response.roles().isEmpty());
        }

        @Test
        void wrongEmail_throwsInvalidCredentials() {
            LoginRequest request = new LoginRequest("no@test.com", "pass", null);
            when(userRepository.findByEmail("no@test.com")).thenReturn(Optional.empty());

            assertThrows(InvalidCredentialsException.class, () -> authService.login(request));
        }

        @Test
        void wrongPassword_throwsInvalidCredentials() {
            LoginRequest request = new LoginRequest("user@test.com", "wrong", null);
            when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(testUser));
            when(passwordEncoder.matches("wrong", "encoded-pass")).thenReturn(false);

            assertThrows(InvalidCredentialsException.class, () -> authService.login(request));
        }

        @Test
        void suspendedUser_throwsUserSuspended() {
            testUser.setStatus(UserStatus.SUSPENDED);
            LoginRequest request = new LoginRequest("user@test.com", "pass", null);
            when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(testUser));
            when(passwordEncoder.matches("pass", "encoded-pass")).thenReturn(true);

            assertThrows(UserSuspendedException.class, () -> authService.login(request));
        }

        @Test
        void specifiedTenantNotFound_throwsTenantAccessDenied() {
            LoginRequest request = new LoginRequest("user@test.com", "pass", 999L);
            when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(testUser));
            when(passwordEncoder.matches("pass", "encoded-pass")).thenReturn(true);
            when(membershipRepository.findByUserId(1L)).thenReturn(List.of(activeMembership));

            assertThrows(TenantAccessDeniedException.class, () -> authService.login(request));
        }

        @Test
        void onlyInactiveMemberships_returnsTokenWithoutTenant() {
            Membership inactiveMembership = TestEntityUtil.withId(Membership.builder()
                    .user(testUser)
                    .tenant(testTenant)
                    .status(MembershipStatus.REMOVED)
                    .roles(Set.of(ownerRole))
                    .build(), 201L);

            LoginRequest request = new LoginRequest("user@test.com", "pass", null);
            when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(testUser));
            when(passwordEncoder.matches("pass", "encoded-pass")).thenReturn(true);
            when(membershipRepository.findByUserId(1L)).thenReturn(List.of(inactiveMembership));

            AuthResponse response = authService.login(request);

            assertNull(response.tenantId());
            assertTrue(response.roles().isEmpty());
        }
    }

    // ========== createTenant ==========
    @Nested
    class CreateTenant {

        @Test
        void success_createsTenantWithOwnerMembership() {
            CreateTenantRequest request = new CreateTenantRequest("NewOrg");
            when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
            when(tenantRepository.save(any(Tenant.class))).thenReturn(testTenant);
            when(roleRepository.findByName("OWNER")).thenReturn(Optional.of(ownerRole));
            when(membershipRepository.save(any(Membership.class))).thenReturn(activeMembership);

            AuthResponse response = authService.createTenant(request, 1L);

            assertEquals(testTenant.getId(), response.tenantId());
            assertEquals(List.of("OWNER"), response.roles());
            verify(membershipRepository).save(any(Membership.class));
        }

        @Test
        void userNotFound_throwsResourceNotFound() {
            CreateTenantRequest request = new CreateTenantRequest("Org");
            when(userRepository.findById(999L)).thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class, () -> authService.createTenant(request, 999L));
        }

        @Test
        void ownerRoleMissing_throwsResourceNotFound() {
            CreateTenantRequest request = new CreateTenantRequest("Org");
            when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
            when(tenantRepository.save(any(Tenant.class))).thenReturn(testTenant);
            when(roleRepository.findByName("OWNER")).thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class, () -> authService.createTenant(request, 1L));
        }
    }

    // ========== refresh ==========
    @Nested
    class Refresh {

        private RefreshToken validStoredToken;

        @BeforeEach
        void setUp() {
            validStoredToken = TestEntityUtil.withId(RefreshToken.builder()
                    .user(testUser)
                    .tenant(testTenant)
                    .tokenHash(TokenHashUtil.sha256("valid-refresh-token"))
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .revoked(false)
                    .build(), 300L);
        }

        @Test
        void success_rotatesTokenAndReturnsNewPair() {
            RefreshRequest request = new RefreshRequest("valid-refresh-token");
            String tokenHash = TokenHashUtil.sha256("valid-refresh-token");
            when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(validStoredToken));
            when(membershipRepository.findByUserIdAndTenantId(1L, 100L)).thenReturn(Optional.of(activeMembership));
            when(jwtTokenProvider.generateRefreshToken()).thenReturn("new-refresh-token");
            when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

            AuthResponse response = authService.refresh(request);

            assertNotNull(response.accessToken());
            assertEquals("new-refresh-token", response.refreshToken());
            assertEquals(testUser.getId(), response.userId());
            assertEquals(testTenant.getId(), response.tenantId());
            assertTrue(validStoredToken.isRevoked());
        }

        @Test
        void success_withoutTenant_returnsTokenWithoutTenantContext() {
            RefreshToken noTenantToken = TestEntityUtil.withId(RefreshToken.builder()
                    .user(testUser)
                    .tenant(null)
                    .tokenHash(TokenHashUtil.sha256("no-tenant-token"))
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .revoked(false)
                    .build(), 301L);

            RefreshRequest request = new RefreshRequest("no-tenant-token");
            String hash = TokenHashUtil.sha256("no-tenant-token");
            when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(noTenantToken));
            when(jwtTokenProvider.generateRefreshToken()).thenReturn("new-token");
            when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

            AuthResponse response = authService.refresh(request);

            assertNull(response.tenantId());
            assertTrue(response.roles().isEmpty());
        }

        @Test
        void invalidToken_throwsInvalidTokenException() {
            RefreshRequest request = new RefreshRequest("unknown-token");
            String hash = TokenHashUtil.sha256("unknown-token");
            when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.empty());

            assertThrows(InvalidTokenException.class, () -> authService.refresh(request));
        }

        @Test
        void revokedToken_triggersReplayDetectionAndThrows() {
            validStoredToken.setRevoked(true);
            RefreshRequest request = new RefreshRequest("valid-refresh-token");
            String hash = TokenHashUtil.sha256("valid-refresh-token");
            when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(validStoredToken));

            assertThrows(InvalidTokenException.class, () -> authService.refresh(request));
            verify(refreshTokenRepository).revokeAllByUserIdAndTenantId(1L, 100L);
        }

        @Test
        void revokedToken_withoutTenant_skipsChainRevocation() {
            RefreshToken noTenantRevoked = TestEntityUtil.withId(RefreshToken.builder()
                    .user(testUser)
                    .tenant(null)
                    .tokenHash(TokenHashUtil.sha256("revoked-no-tenant"))
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .revoked(true)
                    .build(), 302L);

            RefreshRequest request = new RefreshRequest("revoked-no-tenant");
            String hash = TokenHashUtil.sha256("revoked-no-tenant");
            when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(noTenantRevoked));

            assertThrows(InvalidTokenException.class, () -> authService.refresh(request));
            verify(refreshTokenRepository, never()).revokeAllByUserIdAndTenantId(any(), any());
        }

        @Test
        void expiredToken_revokesAndThrows() {
            validStoredToken.setExpiresAt(Instant.now().minusSeconds(60));
            RefreshRequest request = new RefreshRequest("valid-refresh-token");
            String hash = TokenHashUtil.sha256("valid-refresh-token");
            when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(validStoredToken));

            assertThrows(InvalidTokenException.class, () -> authService.refresh(request));
            assertTrue(validStoredToken.isRevoked());
            verify(refreshTokenRepository).save(validStoredToken);
        }

        @Test
        void membershipNoLongerExists_throwsTenantAccessDenied() {
            RefreshRequest request = new RefreshRequest("valid-refresh-token");
            String hash = TokenHashUtil.sha256("valid-refresh-token");
            when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(validStoredToken));
            when(membershipRepository.findByUserIdAndTenantId(1L, 100L)).thenReturn(Optional.empty());

            assertThrows(TenantAccessDeniedException.class, () -> authService.refresh(request));
        }

        @Test
        void membershipInactive_throwsTenantAccessDenied() {
            Membership inactiveMembership = TestEntityUtil.withId(Membership.builder()
                    .user(testUser)
                    .tenant(testTenant)
                    .status(MembershipStatus.REMOVED)
                    .roles(Set.of(ownerRole))
                    .build(), 203L);

            RefreshRequest request = new RefreshRequest("valid-refresh-token");
            String hash = TokenHashUtil.sha256("valid-refresh-token");
            when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(validStoredToken));
            when(membershipRepository.findByUserIdAndTenantId(1L, 100L)).thenReturn(Optional.of(inactiveMembership));

            assertThrows(TenantAccessDeniedException.class, () -> authService.refresh(request));
        }
    }

    // ========== logout ==========
    @Nested
    class Logout {

        @Test
        void success_revokesToken() {
            RefreshToken token = TestEntityUtil.withId(RefreshToken.builder()
                    .user(testUser)
                    .tenant(testTenant)
                    .tokenHash(TokenHashUtil.sha256("logout-token"))
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .revoked(false)
                    .build(), 400L);

            RefreshRequest request = new RefreshRequest("logout-token");
            String hash = TokenHashUtil.sha256("logout-token");
            when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(token));

            authService.logout(request);

            assertTrue(token.isRevoked());
            verify(refreshTokenRepository).save(token);
        }

        @Test
        void unknownToken_doesNothing() {
            RefreshRequest request = new RefreshRequest("unknown-token");
            String hash = TokenHashUtil.sha256("unknown-token");
            when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.empty());

            authService.logout(request);

            verify(refreshTokenRepository, never()).save(any());
        }
    }

    // ========== switchTenant ==========
    @Nested
    class SwitchTenant {

        @Test
        void success_returnsNewTokensForTargetTenant() {
            SwitchTenantRequest request = new SwitchTenantRequest(100L);
            when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
            when(membershipRepository.findByUserIdAndTenantId(1L, 100L)).thenReturn(Optional.of(activeMembership));

            AuthResponse response = authService.switchTenant(request, 1L);

            assertEquals(100L, response.tenantId());
            assertEquals(List.of("OWNER"), response.roles());
        }

        @Test
        void userNotFound_throwsResourceNotFound() {
            SwitchTenantRequest request = new SwitchTenantRequest(100L);
            when(userRepository.findById(999L)).thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class, () -> authService.switchTenant(request, 999L));
        }

        @Test
        void noMembership_throwsTenantAccessDenied() {
            SwitchTenantRequest request = new SwitchTenantRequest(999L);
            when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
            when(membershipRepository.findByUserIdAndTenantId(1L, 999L)).thenReturn(Optional.empty());

            assertThrows(TenantAccessDeniedException.class, () -> authService.switchTenant(request, 1L));
        }

        @Test
        void inactiveMembership_throwsTenantAccessDenied() {
            Membership inactive = TestEntityUtil.withId(Membership.builder()
                    .user(testUser)
                    .tenant(testTenant)
                    .status(MembershipStatus.REMOVED)
                    .roles(Set.of(ownerRole))
                    .build(), 204L);

            SwitchTenantRequest request = new SwitchTenantRequest(100L);
            when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
            when(membershipRepository.findByUserIdAndTenantId(1L, 100L)).thenReturn(Optional.of(inactive));

            assertThrows(TenantAccessDeniedException.class, () -> authService.switchTenant(request, 1L));
        }
    }
}
