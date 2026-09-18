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
import com.feature_flag.platform.common.config.Messages;
import com.feature_flag.platform.common.exception.EmailAlreadyExistsException;
import com.feature_flag.platform.common.exception.ForbiddenException;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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

        /**
         * Registration is anonymous, so its rejection must not double as a user-directory lookup. The
         * message the caller receives is resolved from an argument-free key: the probed address is
         * never handed to the message formatter, so there is no path by which it can come back out.
         * {@link com.feature_flag.platform.common.config.AuthMessageCatalogueTest} covers the other
         * half — that the text behind that key does not assert the account exists either.
         */
        @Test
        void duplicateEmail_conflictMessageNeitherEchoesTheProbedAddressNorNamesTheOldKey() {
            RegisterRequest request = new RegisterRequest("dup@test.com", "pass12345", "Jane", null);
            when(userRepository.existsByEmail("dup@test.com")).thenReturn(true);

            EmailAlreadyExistsException thrown =
                    assertThrows(EmailAlreadyExistsException.class, () -> authService.register(request));

            assertEquals(Messages.AUTH_REGISTRATION_CONFLICT, thrown.getMessage());
            assertFalse(thrown.getMessage().contains("dup@test.com"));
            i18nMock.verify(() -> I18N.getMessage(Messages.AUTH_REGISTRATION_CONFLICT));
            i18nMock.verify(() -> I18N.getMessage(anyString(), eq("dup@test.com")), never());
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

        /**
         * The enumeration oracle here was latency, not content: a known address always paid for a
         * BCrypt comparison, an unknown one returned as soon as the index lookup missed. Asserting
         * wall-clock time would be flaky on a shared CI box, so this asserts the mechanism instead —
         * the miss path really does hand the submitted password to the encoder, against the decoy hash.
         */
        @Test
        void unknownEmail_stillPaysForABcryptComparisonAgainstTheDecoyHash() {
            LoginRequest request = new LoginRequest("no@test.com", "pass", null);
            when(userRepository.findByEmail("no@test.com")).thenReturn(Optional.empty());

            assertThrows(InvalidCredentialsException.class, () -> authService.login(request));

            verify(passwordEncoder).matches("pass", AuthServiceImpl.DUMMY_PASSWORD_HASH);
        }

        /**
         * The decoy only equalises the timing if BCrypt actually runs against it.
         * {@code BCryptPasswordEncoder.matches} bails out in microseconds on anything that does not
         * parse as a BCrypt digest, which would silently restore the gap, so this pins the constant to
         * the shape the encoder's own BCRYPT_PATTERN accepts, at the strength SecurityConfig's bean
         * uses — and checks a real encoder neither accepts a password against it nor rejects it as
         * malformed.
         */
        @Test
        void decoyHash_isAWellFormedBcryptDigestAtTheConfiguredStrength() {
            assertTrue(AuthServiceImpl.DUMMY_PASSWORD_HASH.matches("^\\$2[aby]\\$10\\$[./0-9A-Za-z]{53}$"),
                    "decoy hash must be a well-formed BCrypt digest at strength 10, was: "
                            + AuthServiceImpl.DUMMY_PASSWORD_HASH);
            assertFalse(new BCryptPasswordEncoder().matches("pass", AuthServiceImpl.DUMMY_PASSWORD_HASH),
                    "the decoy must never verify any password");
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

        /**
         * The status check used to be a denylist naming SUSPENDED, so INACTIVE — and any status added
         * to the enum later — authenticated normally. It is now an allowlist: only ACTIVE gets a
         * session. SUSPENDED keeps its own message and exception; everything else is a plain 403.
         */
        @Test
        void inactiveUser_isRefusedAndGetsNoTokens() {
            testUser.setStatus(UserStatus.INACTIVE);
            LoginRequest request = new LoginRequest("user@test.com", "pass", null);
            when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(testUser));
            when(passwordEncoder.matches("pass", "encoded-pass")).thenReturn(true);

            ForbiddenException thrown =
                    assertThrows(ForbiddenException.class, () -> authService.login(request));

            assertEquals(ForbiddenException.class, thrown.getClass(),
                    "INACTIVE is not suspension and must not borrow the suspension message");
            assertEquals(Messages.AUTH_USER_NOT_ACTIVE, thrown.getMessage());
            assertEquals("USER_NOT_ACTIVE", thrown.getErrorCode());
            verify(tokenService, never()).createTokenPair(any(), any(), any());
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

        /**
         * findByUserId has no ORDER BY, so two logins with identical data could previously pick
         * different default tenants — and therefore mint different roles claims — depending on what
         * the database chose to return first. The two rounds here differ only in the repository's
         * ordering; the tenant handed to createTokenPair must not.
         */
        @Test
        void defaultTenant_isTheOldestActiveMembershipWhateverOrderTheRepositoryReturns() {
            Tenant laterTenant = TestEntityUtil.withId(Tenant.builder().name("LaterOrg").build(), 101L);
            Membership olderMembership = TestEntityUtil.withId(Membership.builder()
                    .user(testUser).tenant(testTenant).status(MembershipStatus.ACTIVE)
                    .roles(Set.of(ownerRole)).build(), 200L);
            Membership newerMembership = TestEntityUtil.withId(Membership.builder()
                    .user(testUser).tenant(laterTenant).status(MembershipStatus.ACTIVE)
                    .roles(Set.of(ownerRole)).build(), 300L);

            LoginRequest request = new LoginRequest("user@test.com", "pass", null);
            when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(testUser));
            when(passwordEncoder.matches("pass", "encoded-pass")).thenReturn(true);

            when(membershipRepository.findByUserId(1L)).thenReturn(List.of(newerMembership, olderMembership));
            authService.login(request);
            when(membershipRepository.findByUserId(1L)).thenReturn(List.of(olderMembership, newerMembership));
            authService.login(request);

            ArgumentCaptor<Tenant> tenantCaptor = ArgumentCaptor.forClass(Tenant.class);
            verify(tokenService, times(2)).createTokenPair(any(), tenantCaptor.capture(), any());
            assertEquals(List.of(testTenant, testTenant), tenantCaptor.getAllValues(),
                    "default tenant must not depend on the order the repository happened to return");
        }

        /** A removed membership never becomes the default, however early the repository returns it. */
        @Test
        void defaultTenant_ignoresNonActiveMembershipsEvenWhenTheySortFirst() {
            Tenant staleTenant = TestEntityUtil.withId(Tenant.builder().name("StaleOrg").build(), 102L);
            Membership removedButOlder = TestEntityUtil.withId(Membership.builder()
                    .user(testUser).tenant(staleTenant).status(MembershipStatus.REMOVED)
                    .roles(Set.of(ownerRole)).build(), 100L);

            LoginRequest request = new LoginRequest("user@test.com", "pass", null);
            when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(testUser));
            when(passwordEncoder.matches("pass", "encoded-pass")).thenReturn(true);
            when(membershipRepository.findByUserId(1L))
                    .thenReturn(List.of(removedButOlder, activeMembership));

            AuthResponse response = authService.login(request);

            assertEquals(testTenant.getId(), response.tenantId());
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
