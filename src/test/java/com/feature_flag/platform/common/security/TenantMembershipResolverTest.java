package com.feature_flag.platform.common.security;

import com.feature_flag.platform.auth.entity.Role;
import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.auth.enums.RoleName;
import com.feature_flag.platform.common.config.I18N;
import com.feature_flag.platform.common.config.Messages;
import com.feature_flag.platform.common.exception.TenantAccessDeniedException;
import com.feature_flag.platform.tenant.entity.Membership;
import com.feature_flag.platform.tenant.entity.Tenant;
import com.feature_flag.platform.tenant.enums.MembershipStatus;
import com.feature_flag.platform.tenant.repository.MembershipRepository;
import com.feature_flag.platform.util.TestEntityUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantMembershipResolverTest {

    @Mock private MembershipRepository membershipRepository;

    @InjectMocks private TenantMembershipResolver resolver;

    private MockedStatic<I18N> i18nMock;
    private Tenant tenant;
    private User user;

    @BeforeEach
    void setUp() {
        i18nMock = mockStatic(I18N.class);
        i18nMock.when(() -> I18N.getMessage(anyString())).thenAnswer(inv -> inv.getArgument(0));
        i18nMock.when(() -> I18N.getMessage(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));

        user = TestEntityUtil.withId(User.builder()
                .email("user@test.com").firstName("John").passwordHash("hash").build(), 1L);
        tenant = TestEntityUtil.withId(Tenant.builder().name("TestOrg").build(), 100L);
    }

    @AfterEach
    void tearDown() {
        i18nMock.close();
    }

    private Membership membership(MembershipStatus status, String... roleNames) {
        Set<Role> roles = java.util.Arrays.stream(roleNames)
                .map(name -> TestEntityUtil.withId(Role.builder().name(name).build(), (long) name.hashCode()))
                .collect(java.util.stream.Collectors.toSet());
        return TestEntityUtil.withId(Membership.builder()
                .user(user).tenant(tenant).status(status).roles(roles).build(), 200L);
    }

    @Test
    void activeMembership_isReturned() {
        Membership active = membership(MembershipStatus.ACTIVE, "OWNER");
        when(membershipRepository.findWithRolesByUserIdAndTenantId(1L, 100L)).thenReturn(Optional.of(active));

        assertSame(active, resolver.requireActiveMembership(1L, 100L));
    }

    @Test
    void noMembership_throwsTenantAccessDenied() {
        when(membershipRepository.findWithRolesByUserIdAndTenantId(1L, 999L)).thenReturn(Optional.empty());

        TenantAccessDeniedException ex = assertThrows(TenantAccessDeniedException.class,
                () -> resolver.requireActiveMembership(1L, 999L));
        assertEquals(Messages.TENANT_NO_MEMBERSHIP, ex.getMessage());
    }

    @Test
    void removedMembership_throwsTenantAccessDenied() {
        when(membershipRepository.findWithRolesByUserIdAndTenantId(1L, 100L))
                .thenReturn(Optional.of(membership(MembershipStatus.REMOVED, "OWNER")));

        TenantAccessDeniedException ex = assertThrows(TenantAccessDeniedException.class,
                () -> resolver.requireActiveMembership(1L, 100L));
        assertEquals(Messages.TENANT_MEMBERSHIP_NOT_ACTIVE, ex.getMessage());
    }

    @Test
    void invitedMembership_throwsTenantAccessDenied() {
        when(membershipRepository.findWithRolesByUserIdAndTenantId(1L, 100L))
                .thenReturn(Optional.of(membership(MembershipStatus.INVITED, "MEMBER")));

        assertThrows(TenantAccessDeniedException.class, () -> resolver.requireActiveMembership(1L, 100L));
    }

    @Test
    void customMessageKeys_areUsed() {
        when(membershipRepository.findWithRolesByUserIdAndTenantId(1L, 100L)).thenReturn(Optional.empty());

        TenantAccessDeniedException ex = assertThrows(TenantAccessDeniedException.class,
                () -> resolver.requireActiveMembership(1L, 100L,
                        Messages.TENANT_MEMBERSHIP_NO_LONGER_EXISTS, Messages.TENANT_MEMBERSHIP_NO_LONGER_ACTIVE));
        assertEquals(Messages.TENANT_MEMBERSHIP_NO_LONGER_EXISTS, ex.getMessage());
    }

    @Test
    void customInactiveMessageKey_isUsed() {
        when(membershipRepository.findWithRolesByUserIdAndTenantId(1L, 100L))
                .thenReturn(Optional.of(membership(MembershipStatus.REMOVED, "OWNER")));

        TenantAccessDeniedException ex = assertThrows(TenantAccessDeniedException.class,
                () -> resolver.requireActiveMembership(1L, 100L,
                        Messages.TENANT_MEMBERSHIP_NO_LONGER_EXISTS, Messages.TENANT_MEMBERSHIP_NO_LONGER_ACTIVE));
        assertEquals(Messages.TENANT_MEMBERSHIP_NO_LONGER_ACTIVE, ex.getMessage());
    }

    @Test
    void requireRole_returnsTheMembershipWhenItHoldsTheRole() {
        Membership active = membership(MembershipStatus.ACTIVE, "OWNER");
        when(membershipRepository.findWithRolesByUserIdAndTenantId(1L, 100L)).thenReturn(Optional.of(active));

        assertSame(active, resolver.requireActiveMembershipWithAnyRole(1L, 100L, RoleName.OWNER));
    }

    @Test
    void requireRole_anyOneOfSeveralIsEnough() {
        when(membershipRepository.findWithRolesByUserIdAndTenantId(1L, 100L))
                .thenReturn(Optional.of(membership(MembershipStatus.ACTIVE, "ADMIN")));

        assertNotNull(resolver.requireActiveMembershipWithAnyRole(1L, 100L, RoleName.OWNER, RoleName.ADMIN));
    }

    /** The roles come from the membership in *this* tenant — never from the caller's token. */
    @Test
    void requireRole_withoutThatRoleInThisTenant_throwsTenantAccessDenied() {
        when(membershipRepository.findWithRolesByUserIdAndTenantId(1L, 100L))
                .thenReturn(Optional.of(membership(MembershipStatus.ACTIVE, "VIEWER")));

        TenantAccessDeniedException ex = assertThrows(TenantAccessDeniedException.class,
                () -> resolver.requireActiveMembershipWithAnyRole(1L, 100L, RoleName.OWNER));
        assertEquals(Messages.TENANT_ROLE_REQUIRED, ex.getMessage());
    }

    @Test
    void requireRole_inactiveMembership_throwsBeforeTheRoleIsEvenConsidered() {
        when(membershipRepository.findWithRolesByUserIdAndTenantId(1L, 100L))
                .thenReturn(Optional.of(membership(MembershipStatus.REMOVED, "OWNER")));

        TenantAccessDeniedException ex = assertThrows(TenantAccessDeniedException.class,
                () -> resolver.requireActiveMembershipWithAnyRole(1L, 100L, RoleName.OWNER));
        assertEquals(Messages.TENANT_MEMBERSHIP_NOT_ACTIVE, ex.getMessage());
    }

    @Test
    void roleNames_mapsEveryRole() {
        List<String> names = resolver.roleNames(membership(MembershipStatus.ACTIVE, "OWNER", "ADMIN"));

        assertEquals(2, names.size());
        assertTrue(names.containsAll(List.of("OWNER", "ADMIN")));
    }

    @Test
    void roleNames_emptyWhenNoRoles() {
        assertTrue(resolver.roleNames(membership(MembershipStatus.ACTIVE)).isEmpty());
    }
}
