package com.feature_flag.platform.tenant.service;

import com.feature_flag.platform.auth.entity.Role;
import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.auth.enums.UserStatus;
import com.feature_flag.platform.auth.repository.RoleRepository;
import com.feature_flag.platform.auth.repository.UserRepository;
import com.feature_flag.platform.auth.enums.RoleName;
import com.feature_flag.platform.common.config.I18N;
import com.feature_flag.platform.common.exception.ResourceNotFoundException;
import com.feature_flag.platform.common.exception.TenantAccessDeniedException;
import com.feature_flag.platform.common.security.TenantMembershipResolver;
import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.feature.repository.EnvRepository;
import com.feature_flag.platform.feature.repository.FeatureEnvStatusRepository;
import com.feature_flag.platform.feature.repository.FeatureRepository;
import com.feature_flag.platform.feature.repository.FeatureRuleRepository;
import com.feature_flag.platform.feature.repository.FeatureUserOverrideRepository;
import com.feature_flag.platform.tenant.dto.TenantCreationRequest;
import com.feature_flag.platform.tenant.dto.TenantList;
import com.feature_flag.platform.tenant.dto.TenantResponse;
import com.feature_flag.platform.tenant.dto.UpdateTenantRequest;
import com.feature_flag.platform.tenant.entity.Membership;
import com.feature_flag.platform.tenant.entity.Tenant;
import com.feature_flag.platform.tenant.enums.MembershipStatus;
import com.feature_flag.platform.tenant.enums.TenantPlan;
import com.feature_flag.platform.tenant.mapper.TenantMapper;
import com.feature_flag.platform.tenant.repository.ApiKeyRepository;
import com.feature_flag.platform.tenant.repository.MembershipRepository;
import com.feature_flag.platform.tenant.repository.TenantRefreshTokenRepository;
import com.feature_flag.platform.tenant.repository.TenantRepository;
import com.feature_flag.platform.tenant.repository.UserInvitationRepository;
import com.feature_flag.platform.util.TestEntityUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantServiceTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private UserRepository userRepository;
    @Mock private MembershipRepository membershipRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private ApiKeyRepository apiKeyRepository;
    @Mock private UserInvitationRepository userInvitationRepository;
    @Mock private TenantRefreshTokenRepository tenantRefreshTokenRepository;
    @Mock private FeatureRuleRepository featureRuleRepository;
    @Mock private FeatureUserOverrideRepository featureUserOverrideRepository;
    @Mock private FeatureEnvStatusRepository featureEnvStatusRepository;
    @Mock private FeatureRepository featureRepository;
    @Mock private EnvRepository envRepository;
    @Mock private TenantMembershipResolver tenantMembershipResolver;

    private TenantMapper tenantMapper = new TenantMapper();
    private TenantServiceImpl tenantService;

    private MockedStatic<I18N> i18nMock;
    private User testUser;
    private Tenant testTenant;
    private Role ownerRole;
    private TenantUserDetails userDetails;

    @BeforeEach
    void setUp() {
        tenantService = new TenantServiceImpl(tenantRepository, userRepository, membershipRepository,
                roleRepository, apiKeyRepository, userInvitationRepository, tenantRefreshTokenRepository,
                featureRuleRepository, featureUserOverrideRepository, featureEnvStatusRepository,
                featureRepository, envRepository, tenantMembershipResolver, tenantMapper);

        i18nMock = mockStatic(I18N.class);
        i18nMock.when(() -> I18N.getMessage(anyString())).thenAnswer(inv -> inv.getArgument(0));
        i18nMock.when(() -> I18N.getMessage(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));

        testUser = TestEntityUtil.withId(User.builder()
                .email("user@test.com").firstName("John").passwordHash("hash")
                .status(UserStatus.ACTIVE).build(), 1L);
        testTenant = TestEntityUtil.withId(Tenant.builder()
                .name("TestOrg").plan(TenantPlan.FREE).build(), 100L);
        ownerRole = TestEntityUtil.withId(Role.builder().name("OWNER").build(), 10L);
        userDetails = new TenantUserDetails(1L, "user@test.com", null, List.of());
    }

    @AfterEach
    void tearDown() { i18nMock.close(); }

    @Test
    void createTenant_success() {
        TenantCreationRequest request = new TenantCreationRequest("NewOrg");
        when(tenantRepository.save(any(Tenant.class))).thenReturn(testTenant);
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(roleRepository.findByName("OWNER")).thenReturn(Optional.of(ownerRole));
        when(membershipRepository.save(any(Membership.class))).thenAnswer(inv -> inv.getArgument(0));

        TenantResponse response = tenantService.createTenant(request, userDetails);

        assertEquals("100", response.id());
        assertEquals("TestOrg", response.name());
        assertEquals(TenantPlan.FREE, response.plan());
        assertEquals(1, response.roles().size());
        assertEquals("OWNER", response.roles().get(0).name());
        verify(membershipRepository).save(any(Membership.class));
    }

    @Test
    void createTenant_userNotFound_throws() {
        TenantCreationRequest request = new TenantCreationRequest("Org");
        when(tenantRepository.save(any(Tenant.class))).thenReturn(testTenant);
        when(userRepository.findById(1L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> tenantService.createTenant(request, userDetails));
    }

    @Test
    void createTenant_ownerRoleMissing_throws() {
        TenantCreationRequest request = new TenantCreationRequest("Org");
        when(tenantRepository.save(any(Tenant.class))).thenReturn(testTenant);
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(roleRepository.findByName("OWNER")).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> tenantService.createTenant(request, userDetails));
    }

    @Test
    void getTenantsForUser_returnsTenantList() {
        Membership membership = TestEntityUtil.withId(Membership.builder()
                .user(testUser).tenant(testTenant).status(MembershipStatus.ACTIVE)
                .roles(Set.of(ownerRole)).build(), 200L);
        when(membershipRepository.findByUserId(1L)).thenReturn(List.of(membership));

        TenantList result = tenantService.getTenantsForUser(userDetails);

        assertEquals(1, result.tenants().size());
        assertEquals("100", result.tenants().get(0).id());
    }

    @Test
    void getTenantsForUser_noMemberships_returnsEmptyList() {
        when(membershipRepository.findByUserId(1L)).thenReturn(Collections.emptyList());
        TenantList result = tenantService.getTenantsForUser(userDetails);
        assertTrue(result.tenants().isEmpty());
    }

    // --- /api/v1/tenants/{tenantId}: authorization is against the PATH tenant -----------------------

    private Membership activeMembership(Role... roles) {
        return TestEntityUtil.withId(Membership.builder()
                .user(testUser).tenant(testTenant).status(MembershipStatus.ACTIVE)
                .roles(Set.of(roles)).build(), 200L);
    }

    @Test
    void getTenant_requiresAnActiveMembershipInThatTenant() {
        Membership membership = activeMembership(ownerRole);
        when(tenantMembershipResolver.requireActiveMembership(1L, 100L)).thenReturn(membership);

        TenantResponse response = tenantService.getTenant(100L, userDetails);

        assertEquals("100", response.id());
        verify(tenantMembershipResolver).requireActiveMembership(1L, 100L);
    }

    /**
     * The old lookup accepted any membership row, ACTIVE or not, so a REMOVED or INVITED member kept read
     * access to the tenant.
     */
    @Test
    void getTenant_withoutAnActiveMembership_isForbidden() {
        when(tenantMembershipResolver.requireActiveMembership(1L, 100L))
                .thenThrow(new TenantAccessDeniedException("not active"));

        assertThrows(TenantAccessDeniedException.class, () -> tenantService.getTenant(100L, userDetails));
    }

    @Test
    void updateTenant_asOwnerOfThatTenant_renamesIt() {
        Membership membership = activeMembership(ownerRole);
        when(tenantMembershipResolver.requireActiveMembershipWithAnyRole(1L, 100L, RoleName.OWNER))
                .thenReturn(membership);

        TenantResponse response = tenantService.updateTenant(100L, new UpdateTenantRequest("Renamed"), userDetails);

        assertEquals("Renamed", response.name());
        assertEquals("Renamed", testTenant.getName());
        verify(tenantRepository).save(testTenant);
    }

    @Test
    void updateTenant_nullName_leavesTheNameAlone() {
        Membership membership = activeMembership(ownerRole);
        when(tenantMembershipResolver.requireActiveMembershipWithAnyRole(1L, 100L, RoleName.OWNER))
                .thenReturn(membership);

        tenantService.updateTenant(100L, new UpdateTenantRequest(null), userDetails);

        assertEquals("TestOrg", testTenant.getName());
    }

    /** A VIEWER in the target tenant used to be able to rename it — updateTenant checked no role at all. */
    @Test
    void updateTenant_withoutOwnerInThatTenant_isForbiddenAndWritesNothing() {
        when(tenantMembershipResolver.requireActiveMembershipWithAnyRole(1L, 100L, RoleName.OWNER))
                .thenThrow(new TenantAccessDeniedException("role required"));

        assertThrows(TenantAccessDeniedException.class,
                () -> tenantService.updateTenant(100L, new UpdateTenantRequest("Hijacked"), userDetails));

        assertEquals("TestOrg", testTenant.getName());
        verify(tenantRepository, never()).save(any(Tenant.class));
    }

    /**
     * The cascade used to stop at memberships and API keys, so any tenant that had ever created a feature
     * or invited anyone died on a foreign-key constraint. Every tenant-owned table is now cleaned up.
     */
    @Test
    void deleteTenant_asOwnerOfThatTenant_removesEveryTenantOwnedTable() {
        when(tenantMembershipResolver.requireActiveMembershipWithAnyRole(1L, 100L, RoleName.OWNER))
                .thenReturn(activeMembership(ownerRole));
        when(tenantRepository.findById(100L)).thenReturn(Optional.of(testTenant));

        tenantService.deleteTenant(100L, userDetails);

        verify(featureRuleRepository).deleteAllByTenantId(100L);
        verify(featureUserOverrideRepository).deleteAllByTenantId(100L);
        verify(featureEnvStatusRepository).deleteAllByTenantId(100L);
        verify(featureRepository).deleteAllByTenantId(100L);
        verify(envRepository).deleteAllByTenantId(100L);
        verify(userInvitationRepository).deleteAllByTenantId(100L);
        verify(apiKeyRepository).deleteAllByTenantId(100L);
        verify(tenantRefreshTokenRepository).detachReplacementChainsByTenantId(100L);
        verify(tenantRefreshTokenRepository).deleteAllByTenantId(100L);
        verify(membershipRepository).deleteMembershipRolesByTenantId(100L);
        verify(membershipRepository).deleteAllByTenantId(100L);
        verify(tenantRepository).delete(testTenant);
    }

    /**
     * Ordering is the whole ballgame with foreign keys: every child has to go before the row it points at.
     * Pinned here because the bulk statements give the database no chance to work it out for itself.
     */
    @Test
    void deleteTenant_deletesChildrenBeforeTheRowsTheyPointAt() {
        when(tenantMembershipResolver.requireActiveMembershipWithAnyRole(1L, 100L, RoleName.OWNER))
                .thenReturn(activeMembership(ownerRole));
        when(tenantRepository.findById(100L)).thenReturn(Optional.of(testTenant));

        tenantService.deleteTenant(100L, userDetails);

        InOrder inOrder = inOrder(featureRuleRepository, featureUserOverrideRepository,
                featureEnvStatusRepository, featureRepository, envRepository,
                tenantRefreshTokenRepository, membershipRepository, tenantRepository);
        // Rules and overrides hang off feature_env_status...
        inOrder.verify(featureRuleRepository).deleteAllByTenantId(100L);
        inOrder.verify(featureUserOverrideRepository).deleteAllByTenantId(100L);
        // ...which points at both feature and env...
        inOrder.verify(featureEnvStatusRepository).deleteAllByTenantId(100L);
        inOrder.verify(featureRepository).deleteAllByTenantId(100L);
        inOrder.verify(envRepository).deleteAllByTenantId(100L);
        // ...refresh tokens point at each other, so the chain is broken before the delete...
        inOrder.verify(tenantRefreshTokenRepository).detachReplacementChainsByTenantId(100L);
        inOrder.verify(tenantRefreshTokenRepository).deleteAllByTenantId(100L);
        // ...membership_roles points at memberships, which a bulk delete would not clean up...
        inOrder.verify(membershipRepository).deleteMembershipRolesByTenantId(100L);
        inOrder.verify(membershipRepository).deleteAllByTenantId(100L);
        // ...and the tenant row is last, once nothing references it.
        inOrder.verify(tenantRepository).delete(testTenant);
    }

    /** Nothing to delete is not an error: a brand-new tenant has no children at all. */
    @Test
    void deleteTenant_withNoChildRows_stillDeletesTheTenant() {
        when(tenantMembershipResolver.requireActiveMembershipWithAnyRole(1L, 100L, RoleName.OWNER))
                .thenReturn(activeMembership(ownerRole));
        when(tenantRepository.findById(100L)).thenReturn(Optional.of(testTenant));
        // All the bulk deletes return 0 by Mockito's default.

        tenantService.deleteTenant(100L, userDetails);

        verify(tenantRepository).delete(testTenant);
    }

    /**
     * The exploit, at the service layer: {@code deleteTenant} performed no membership lookup whatsoever, so
     * any caller who got past the (wrongly scoped) role filter could delete any tenant by id.
     */
    @Test
    void deleteTenant_withoutOwnerInThatTenant_isForbiddenAndDeletesNothing() {
        when(tenantMembershipResolver.requireActiveMembershipWithAnyRole(1L, 100L, RoleName.OWNER))
                .thenThrow(new TenantAccessDeniedException("role required"));

        assertThrows(TenantAccessDeniedException.class, () -> tenantService.deleteTenant(100L, userDetails));

        verify(tenantRepository, never()).delete(any(Tenant.class));
        verify(tenantRepository, never()).findById(anyLong());
        // Not one row of the cascade may have been touched before the refusal.
        verifyNoInteractions(apiKeyRepository, userInvitationRepository, tenantRefreshTokenRepository,
                membershipRepository, featureRuleRepository, featureUserOverrideRepository,
                featureEnvStatusRepository, featureRepository, envRepository);
    }

    /** Authorization runs before the lookup, so a missing tenant is a 403, not a 404 existence oracle. */
    @Test
    void deleteTenant_authorizesBeforeItLooksTheTenantUp() {
        when(tenantMembershipResolver.requireActiveMembershipWithAnyRole(1L, 999L, RoleName.OWNER))
                .thenThrow(new TenantAccessDeniedException("no membership"));

        assertThrows(TenantAccessDeniedException.class, () -> tenantService.deleteTenant(999L, userDetails));

        verifyNoInteractions(tenantRepository);
    }

    @Test
    void deleteTenant_tenantRowMissing_throwsNotFound() {
        when(tenantMembershipResolver.requireActiveMembershipWithAnyRole(1L, 100L, RoleName.OWNER))
                .thenReturn(activeMembership(ownerRole));
        when(tenantRepository.findById(100L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> tenantService.deleteTenant(100L, userDetails));

        // The 404 is decided before the cascade starts, so a vanished tenant deletes nothing either.
        verifyNoInteractions(featureRuleRepository, featureUserOverrideRepository,
                featureEnvStatusRepository, featureRepository, envRepository, apiKeyRepository,
                userInvitationRepository, tenantRefreshTokenRepository, membershipRepository);
    }
}
