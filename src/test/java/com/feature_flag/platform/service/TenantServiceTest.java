package com.feature_flag.platform.service;

import com.feature_flag.platform.config.I18N;
import com.feature_flag.platform.dto.tenant.TenantCreationRequest;
import com.feature_flag.platform.dto.tenant.TenantList;
import com.feature_flag.platform.dto.tenant.TenantResponse;
import com.feature_flag.platform.entity.Membership;
import com.feature_flag.platform.entity.Role;
import com.feature_flag.platform.entity.Tenant;
import com.feature_flag.platform.entity.User;
import com.feature_flag.platform.enums.MembershipStatus;
import com.feature_flag.platform.enums.TenantPlan;
import com.feature_flag.platform.enums.UserStatus;
import com.feature_flag.platform.exception.ResourceNotFoundException;
import com.feature_flag.platform.repository.MembershipRepository;
import com.feature_flag.platform.repository.RoleRepository;
import com.feature_flag.platform.repository.TenantRepository;
import com.feature_flag.platform.repository.UserRepository;
import com.feature_flag.platform.security.TenantUserDetails;
import com.feature_flag.platform.util.TestEntityUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantServiceTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private UserRepository userRepository;
    @Mock private MembershipRepository membershipRepository;
    @Mock private RoleRepository roleRepository;

    @InjectMocks private TenantService tenantService;

    private MockedStatic<I18N> i18nMock;

    private User testUser;
    private Tenant testTenant;
    private Role ownerRole;
    private TenantUserDetails userDetails;

    @BeforeEach
    void setUp() {
        i18nMock = mockStatic(I18N.class);
        i18nMock.when(() -> I18N.getMessage(anyString())).thenAnswer(inv -> inv.getArgument(0));
        i18nMock.when(() -> I18N.getMessage(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));

        testUser = TestEntityUtil.withId(User.builder()
                .email("user@test.com")
                .firstName("John")
                .passwordHash("hash")
                .status(UserStatus.ACTIVE)
                .build(), 1L);

        testTenant = TestEntityUtil.withId(Tenant.builder()
                .name("TestOrg")
                .plan(TenantPlan.FREE)
                .build(), 100L);

        ownerRole = TestEntityUtil.withId(Role.builder()
                .name("OWNER")
                .build(), 10L);

        userDetails = new TenantUserDetails(1L, "user@test.com", null, List.of());
    }

    @AfterEach
    void tearDown() {
        i18nMock.close();
    }

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
                .user(testUser)
                .tenant(testTenant)
                .status(MembershipStatus.ACTIVE)
                .roles(Set.of(ownerRole))
                .build(), 200L);

        when(membershipRepository.findByUserId(1L)).thenReturn(List.of(membership));

        TenantList result = tenantService.getTenantsForUser(userDetails);

        assertEquals(1, result.tenants().size());
        assertEquals("100", result.tenants().get(0).id());
        assertEquals("TestOrg", result.tenants().get(0).name());
    }

    @Test
    void getTenantsForUser_noMemberships_returnsEmptyList() {
        when(membershipRepository.findByUserId(1L)).thenReturn(Collections.emptyList());

        TenantList result = tenantService.getTenantsForUser(userDetails);

        assertTrue(result.tenants().isEmpty());
    }
}
