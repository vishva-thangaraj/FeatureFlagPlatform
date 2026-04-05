package com.feature_flag.platform.tenant.service;

import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.tenant.dto.TenantCreationRequest;
import com.feature_flag.platform.tenant.dto.TenantList;
import com.feature_flag.platform.tenant.dto.TenantResponse;
import com.feature_flag.platform.tenant.dto.UpdateTenantRequest;

public interface TenantService {

    TenantResponse createTenant(TenantCreationRequest request, TenantUserDetails userDetails);

    TenantList getTenantsForUser(TenantUserDetails userDetails);

    TenantResponse getTenant(Long tenantId, TenantUserDetails userDetails);

    TenantResponse updateTenant(Long tenantId, UpdateTenantRequest request, TenantUserDetails userDetails);

    void deleteTenant(Long tenantId, TenantUserDetails userDetails);
}