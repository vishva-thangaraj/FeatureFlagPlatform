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
     * @throws com.feature_flag.platform.common.exception.TenantAccessDeniedException (403) unless the
     *         caller holds an ACTIVE membership in {@code tenantId} with the OWNER role <em>in that
     *         tenant</em>.
     */
    void deleteTenant(Long tenantId, TenantUserDetails userDetails);
}