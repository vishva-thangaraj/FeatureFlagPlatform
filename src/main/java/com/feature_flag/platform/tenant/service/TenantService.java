package com.feature_flag.platform.tenant.service;

import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.tenant.dto.TenantCreationRequest;
import com.feature_flag.platform.tenant.dto.TenantList;
import com.feature_flag.platform.tenant.dto.TenantResponse;

public interface TenantService {

    TenantResponse createTenant(TenantCreationRequest request, TenantUserDetails userDetails);

    TenantList getTenantsForUser(TenantUserDetails userDetails);
}