package com.feature_flag.platform.auth.service;

import com.feature_flag.platform.auth.dto.AuthResponse;
import com.feature_flag.platform.auth.dto.LoginRequest;
import com.feature_flag.platform.auth.dto.RegisterRequest;
import com.feature_flag.platform.auth.dto.SwitchTenantRequest;
import com.feature_flag.platform.auth.entity.Role;
import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.auth.enums.UserStatus;
import com.feature_flag.platform.auth.repository.RoleRepository;
import com.feature_flag.platform.auth.repository.UserRepository;
import com.feature_flag.platform.common.config.I18N;
import com.feature_flag.platform.common.exception.EmailAlreadyExistsException;
import com.feature_flag.platform.common.exception.InvalidCredentialsException;
import com.feature_flag.platform.common.exception.ResourceNotFoundException;
import com.feature_flag.platform.common.exception.TenantAccessDeniedException;
import com.feature_flag.platform.common.exception.UserSuspendedException;
import com.feature_flag.platform.common.security.TenantMembershipResolver;
import com.feature_flag.platform.tenant.repository.UserInvitationRepository;
import com.feature_flag.platform.tenant.entity.Membership;
import com.feature_flag.platform.tenant.entity.Tenant;
import com.feature_flag.platform.tenant.enums.MembershipStatus;
import com.feature_flag.platform.tenant.repository.MembershipRepository;
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

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private MembershipRepository membershipRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private UserInvitationRepository userInvitationRepository;
    @Mock private TokenService tokenService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private TenantMembershipResolver tenantMembershipResolver;

    @InjectMocks private AuthServiceImpl authService;

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
                .email("user@test.com").firstName("John").lastName("Doe")
                .passwordHash("encoded-pass").status(UserStatus.ACTIVE).build(), 1L);

        testTenant = TestEntityUtil.withId(Tenant.builder().name("TestOrg").build(), 100L);

        ownerRole = TestEntityUtil.withId(Role.builder().name("OWNER").build(), 10L);

        activeMembership = TestEntityUtil.withId(Membership.builder()
                .user(testUser).tenant(testTenant).status(MembershipStatus.ACTIVE)
                .roles(Set.of(ownerRole)).build(), 200L);

        AuthResponse mockResponse = new AuthResponse("access-token", "refresh-token", 1L, "user@test.com", null, List.of(), 900_000L);
        AuthResponse mockResponseWithTenant = new AuthResponse("access-token", "refresh-token", 1L, "user@test.com", 100L, List.of("OWNER"), 900_000L);
        when(tokenService.createTokenPair(any(), isNull(), any())).thenReturn(mockResponse);
        when(tokenService.createTokenPair(any(), eq(testTenant), any())).thenReturn(mockResponseWithTenant);
        when(tenantMembershipResolver.roleNames(any())).thenReturn(List.of("OWNER"));
    }

    @AfterEach
    void tearDown() {
        i18nMock.close();
    }

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
            verify(tokenService).createTokenPair(any(), isNull(), eq(Collections.emptyList()));
        }

        @Test
        void duplicateEmail_throwsEmailAlreadyExistsException() {
            RegisterRequest request = new RegisterRequest("dup@test.com", "pass12345", "Jane", null);
            when(userRepository.existsByEmail("dup@test.com")).thenReturn(true);
            assertThrows(EmailAlreadyExistsException.class, () -> authService.register(request));
            verify(userRepository, never()).save(any());
        }
    }

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
        void success_noMemberships_returnsTokenWithoutTenant() {
            LoginRequest request = new LoginRequest("user@test.com", "pass", null);
            when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(testUser));
            when(passwordEncoder.matches("pass", "encoded-pass")).thenReturn(true);
            when(membershipRepository.findByUserId(1L)).thenReturn(Collections.emptyList());

            AuthResponse response = authService.login(request);

            assertNull(response.tenantId());
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
        void specifiedTenant_resolvesThatMembershipDirectly() {
            LoginRequest request = new LoginRequest("user@test.com", "pass", 100L);
            when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(testUser));
            when(passwordEncoder.matches("pass", "encoded-pass")).thenReturn(true);
            when(tenantMembershipResolver.requireActiveMembership(1L, 100L)).thenReturn(activeMembership);

            AuthResponse response = authService.login(request);

            assertEquals(100L, response.tenantId());
            verify(membershipRepository, never()).findByUserId(any());
        }

        @Test
        void specifiedTenantNotFound_throwsTenantAccessDenied() {
            LoginRequest request = new LoginRequest("user@test.com", "pass", 999L);
            when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(testUser));
            when(passwordEncoder.matches("pass", "encoded-pass")).thenReturn(true);
            when(tenantMembershipResolver.requireActiveMembership(1L, 999L))
                    .thenThrow(new TenantAccessDeniedException("denied"));
            assertThrows(TenantAccessDeniedException.class, () -> authService.login(request));
        }

        @Test
        void specifiedTenantWithNoMemberships_isDeniedRatherThanDowngraded() {
            LoginRequest request = new LoginRequest("user@test.com", "pass", 100L);
            when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(testUser));
            when(passwordEncoder.matches("pass", "encoded-pass")).thenReturn(true);
            when(tenantMembershipResolver.requireActiveMembership(1L, 100L))
                    .thenThrow(new TenantAccessDeniedException("denied"));
            assertThrows(TenantAccessDeniedException.class, () -> authService.login(request));
        }
    }

    @Nested
    class SwitchTenant {
        @Test
        void success_returnsNewTokensForTargetTenant() {
            SwitchTenantRequest request = new SwitchTenantRequest(100L);
            when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
            when(tenantMembershipResolver.requireActiveMembership(1L, 100L)).thenReturn(activeMembership);

            AuthResponse response = authService.switchTenant(request, 1L);

            assertEquals(100L, response.tenantId());
        }

        @Test
        void userNotFound_throwsResourceNotFound() {
            SwitchTenantRequest request = new SwitchTenantRequest(100L);
            when(userRepository.findById(999L)).thenReturn(Optional.empty());
            assertThrows(ResourceNotFoundException.class, () -> authService.switchTenant(request, 999L));
        }

        // Whether the membership is missing or merely inactive is decided by TenantMembershipResolver and
        // covered in TenantMembershipResolverTest; here we only assert the denial reaches the caller.
        @Test
        void membershipDenied_throwsTenantAccessDenied() {
            SwitchTenantRequest request = new SwitchTenantRequest(999L);
            when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
            when(tenantMembershipResolver.requireActiveMembership(1L, 999L))
                    .thenThrow(new TenantAccessDeniedException("denied"));
            assertThrows(TenantAccessDeniedException.class, () -> authService.switchTenant(request, 1L));
        }
    }
}
