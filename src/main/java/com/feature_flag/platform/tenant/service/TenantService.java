package com.feature_flag.platform.tenant.service;

import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.tenant.dto.TenantCreationRequest;
import com.feature_flag.platform.tenant.dto.TenantList;
import com.feature_flag.platform.tenant.dto.TenantResponse;
import com.feature_flag.platform.tenant.dto.UpdateTenantRequest;

public interface TenantService {

    TenantResponse createTenant(TenantCreationRequest request, TenantUserDetails userDetails);

    TenantList getTenantsForUser(TenantUserDetails userDetails);

    /**
     * @throws com.feature_flag.platform.common.exception.TenantAccessDeniedException (403) unless the
     *         caller holds an ACTIVE membership in {@code tenantId}.
     */
    TenantResponse getTenant(Long tenantId, TenantUserDetails userDetails);

    /**
     * @throws com.feature_flag.platform.common.exception.TenantAccessDeniedException (403) unless the
     *         caller holds an ACTIVE membership in {@code tenantId} with the OWNER role <em>in that
     *         tenant</em>.
     */
    TenantResponse updateTenant(Long tenantId, UpdateTenantRequest request, TenantUserDetails userDetails);

    /**
     * Permanently deletes a tenant and everything it owns: its features and their env statuses, rules and
     * user overrides, its environments, invitations, API keys, sessions and memberships.
     *
     * <p><strong>Irreversible.</strong> This is a hard delete, not an archive — see
     * {@code TenantServiceImpl#deleteTenant} for why a soft delete was rejected. It is atomic: the tenant
     * and all of its data go together or the call fails and nothing is removed. Authorization is checked
     * before anything is read or written, so a refused caller destroys nothing.
     *
     * @throws com.feature_flag.platform.common.exception.TenantAccessDeniedException (403) unless the
     *         caller holds an ACTIVE membership in {@code tenantId} with the OWNER role <em>in that
     *         tenant</em>.
     * @throws com.feature_flag.platform.common.exception.ResourceNotFoundException (404) if the caller is
     *         an OWNER of {@code tenantId} but the tenant row is already gone.
     */
    void deleteTenant(Long tenantId, TenantUserDetails userDetails);
}